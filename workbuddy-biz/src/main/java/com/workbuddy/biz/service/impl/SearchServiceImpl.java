package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.SearchService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbRetrievalRunDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.RetrievalRuleResolver;
import com.workbuddy.biz.service.support.RetrievalRunSettings;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbRetrievalRun;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.request.retrieval.SearchDTO;
import com.workbuddy.common.dto.response.retrieval.RetrievalRunVO;
import com.workbuddy.common.dto.response.retrieval.SearchHitVO;
import com.workbuddy.common.dto.response.retrieval.SearchVO;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategyParser;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.FullTextQuery;
import com.workbuddy.worker.indexing.search.IndexHit;
import com.workbuddy.worker.indexing.search.VectorHit;
import com.workbuddy.worker.indexing.search.VectorQuery;
import com.workbuddy.worker.retrieval.RetrievalRuleSpec;
import com.workbuddy.worker.retrieval.RrfFusion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 检索引擎编排实现：唯一执行路径 search(kbId, query, ruleSpec, collectionName)。
 * 生产检索（回退链）与测试台检索（显式版本+规则）同引擎；预留能力经 RetrievalRuleResolver.validate
 * 双保险拒执行；查询向量化跟随集合 EMBED 策略（queryTemplate 口径）；网关失败该通道降级为空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final WbIndexSetDbService indexSetDbService;

    private final WbIndexVersionDbService indexVersionDbService;

    private final WbKnowledgeBaseDbService knowledgeBaseDbService;

    private final WbPipelineStrategyVersionDbService strategyVersionDbService;

    private final MilvusIndexPort milvusIndexPort;


    private final EmbedStrategyParser embedStrategyParser;

    private final RetrievalRuleResolver retrievalRuleResolver;

    private final WbRetrievalRunDbService retrievalRunDbService;

    private final RetrievalRunSettings runSettings;

    @Override
    public SearchVO search(Long knowledgeBaseId, SearchDTO request) {
        long start = System.currentTimeMillis();
        boolean bench = NullUtil.isNotNull(request.getVersionId()) || NullUtil.isNotNull(request.getRuleId());
        ThrowUtil.throwIf(bench && (NullUtil.isNull(request.getVersionId()) || NullUtil.isNull(request.getRuleId())),
                ErrorCode.PARAM_INVALID, "测试台检索需同时指定 versionId 与 ruleId");

        // ① 版本 + 规则（生产=在线+回退链；测试台=显式）
        WbIndexVersion version = resolveVersion(knowledgeBaseId, request, bench);
        WbPipelineStrategyVersion ruleRow = resolveRuleRow(knowledgeBaseId, version, request.getRuleId());
        // 引擎防御：预留项拒执行（注册侧已拦，双保险防数据篡改/历史数据）
        RetrievalRuleSpec spec = retrievalRuleResolver.validate(ruleRow.getConfigSnapshot());
        String collectionName = MilvusIndexPort.collectionName(knowledgeBaseId, version.getVersionNo());

        // ② 双通道召回（FULLTEXT 跳过向量化；VECTOR 跳过全文）
        List<FullTextHit> fullTextHits = List.of();
        List<VectorHit> vectorHits = List.of();
        if (!RetrievalRuleSpec.CHANNEL_VECTOR.equals(spec.getChannel())) {
            fullTextHits = milvusIndexPort.searchFullText(collectionName, FullTextQuery.builder()
                    .keyword(request.getQuery())
                    .limit(spec.getFusion().getPerChannelLimit())
                    .documentId(request.getDocumentId())
                    .owner(request.getOwner())
                    .contentType(request.getContentType())
                    .build());
        }
        if (!RetrievalRuleSpec.CHANNEL_FULLTEXT.equals(spec.getChannel())) {
            List<Float> queryVector = embedQuery(version, request.getQuery());
            if (NullUtil.isNotNull(queryVector)) {
                vectorHits = milvusIndexPort.searchVector(collectionName, VectorQuery.builder()
                        .vector(queryVector)
                        .topK(spec.getFusion().getPerChannelLimit())
                        .documentId(request.getDocumentId())
                        .owner(request.getOwner())
                        .contentType(request.getContentType())
                        .build());
            }
        }

        // ③ 融合/直通（HYBRID=RRF；单通道直通）
        List<SearchHitVO> ordered = orderHits(spec, fullTextHits, vectorHits);

        // ④ 父片展开（子片命中 → 集合内取父片全文；缺失保留子片原文）
        if (RetrievalRuleSpec.MODE_PARENT_EXPAND.equals(spec.getPostprocess().getMode())) {
            ordered = expandParent(collectionName, ordered);
        }

        // ⑤ Top-K（请求覆盖规则）
        int topK = NullUtil.isNotNull(request.getTopK()) ? request.getTopK() : spec.getTopK();
        ordered = ordered.stream().limit(topK).toList();

        SearchVO vo = new SearchVO();
        vo.setQuery(request.getQuery());
        vo.setRuleNameVersion(ruleRow.getName() + "-" + ruleRow.getVersion());
        vo.setVersionNo(version.getVersionNo());
        vo.setElapsedMs(System.currentTimeMillis() - start);
        vo.setHits(ordered);

        // ⑥ 留痕：测试台必记；生产检索按开关（默认关）。快照即证据——对比回放不回跑。
        if (bench || runSettings.recordProduction()) {
            recordRun(knowledgeBaseId, version, ruleRow, request, vo);
        }
        return vo;
    }

    @Override
    public List<RetrievalRunVO> listRuns(Long knowledgeBaseId, int limit) {
        return retrievalRunDbService.listByKb(knowledgeBaseId, limit).stream()
                .map(this::toRunVO)
                .toList();
    }

    @Override
    public List<SearchVO> compareRuns(Long knowledgeBaseId, List<Long> runIds) {
        ThrowUtil.throwIf(NullUtil.isNull(runIds) || runIds.isEmpty(), ErrorCode.PARAM_INVALID, "runIds 不能为空");
        List<WbRetrievalRun> runs = retrievalRunDbService.listByIdsOrdered(runIds);
        List<SearchVO> vos = new ArrayList<>(runs.size());
        for (WbRetrievalRun run : runs) {
            ThrowUtil.throwIf(!knowledgeBaseId.equals(run.getKbId()),
                    ErrorCode.PARAM_INVALID, "运行记录不属于该知识库: " + run.getId());
            SearchVO vo = new SearchVO();
            vo.setQuery(run.getQuery());
            vo.setRuleNameVersion(run.getRuleNameVersion());
            vo.setVersionNo(run.getVersionNo());
            vo.setElapsedMs(NullUtil.isNull(run.getElapsedMs()) ? 0L : run.getElapsedMs().longValue());
            vo.setRunId(run.getId());
            vo.setHits(JsonUtil.toList(run.getResultSnapshot(), SearchHitVO.class));
            vos.add(vo);
        }
        return vos;
    }

    /** 运行记录落库（快照 = 命中列表全字段 JSON；baseline 规则行无 id → rule_id 记 NULL） */
    private void recordRun(Long knowledgeBaseId, WbIndexVersion version, WbPipelineStrategyVersion ruleRow,
                           SearchDTO request, SearchVO vo) {
        WbRetrievalRun run = new WbRetrievalRun();
        run.setKbId(knowledgeBaseId);
        run.setVersionId(version.getId());
        run.setVersionNo(version.getVersionNo());
        run.setRuleId(ruleRow.getId());
        run.setRuleNameVersion(ruleRow.getName() + "-" + ruleRow.getVersion());
        run.setQuery(request.getQuery());
        run.setResultSnapshot(JsonUtil.toJsonStr(vo.getHits()));
        run.setElapsedMs(NullUtil.isNull(vo.getElapsedMs()) ? 0 : vo.getElapsedMs().intValue());
        retrievalRunDbService.save(run);
        vo.setRunId(run.getId());
    }

    private RetrievalRunVO toRunVO(WbRetrievalRun run) {
        RetrievalRunVO vo = new RetrievalRunVO();
        vo.setId(run.getId());
        vo.setVersionId(run.getVersionId());
        vo.setVersionNo(run.getVersionNo());
        vo.setRuleId(run.getRuleId());
        vo.setRuleNameVersion(run.getRuleNameVersion());
        vo.setQuery(run.getQuery());
        vo.setElapsedMs(run.getElapsedMs());
        vo.setCreateTime(run.getCreateTime());
        return vo;
    }

    /** 版本解析：测试台=显式版本行（含候选冻结集，READY/ONLINE 可检索）；生产=在线版本（未发布 40446） */
    private WbIndexVersion resolveVersion(Long knowledgeBaseId, SearchDTO request, boolean bench) {
        if (bench) {
            WbIndexVersion version = indexVersionDbService.getById(request.getVersionId());
            ThrowUtil.throwIf(NullUtil.isNull(version), ErrorCode.INDEX_VERSION_NOT_FOUND);
            ThrowUtil.throwIf(!Set.of(IndexVersionStatus.READY.name(), IndexVersionStatus.ONLINE.name())
                            .contains(version.getStatus()),
                    ErrorCode.INDEX_BUILDING_CONFLICT, "仅就绪/在线版本可检索");
            return version;
        }
        WbIndexSet set = indexSetDbService.getByKb(knowledgeBaseId);
        ThrowUtil.throwIf(NullUtil.isNull(set) || NullUtil.isNull(set.getCurrentPublishedVersionId()),
                ErrorCode.INDEX_NOT_PUBLISHED);
        WbIndexVersion version = indexVersionDbService.getById(set.getCurrentPublishedVersionId());
        ThrowUtil.throwIf(NullUtil.isNull(version), ErrorCode.INDEX_NOT_PUBLISHED);
        return version;
    }

    /** 规则解析：显式 ruleId → 版本行默认 → kb 默认 → 引擎基线（不落库常量） */
    private WbPipelineStrategyVersion resolveRuleRow(
            Long knowledgeBaseId, WbIndexVersion version, Long explicitRuleId) {
        Long ruleId = NullUtil.isNotNull(explicitRuleId) ? explicitRuleId : version.getDefaultRuleId();
        if (NullUtil.isNull(ruleId)) {
            WbKnowledgeBase kb = knowledgeBaseDbService.getById(knowledgeBaseId);
            ruleId = NullUtil.isNull(kb) ? null : kb.getDefaultRuleId();
        }
        if (NullUtil.isNotNull(ruleId)) {
            WbPipelineStrategyVersion row = strategyVersionDbService.getById(ruleId);
            ThrowUtil.throwIf(NullUtil.isNull(row) || !"RETRIEVAL".equals(row.getType()),
                    ErrorCode.RETRIEVAL_RULE_NOT_FOUND);
            return row;
        }
        WbPipelineStrategyVersion baseline = new WbPipelineStrategyVersion();
        baseline.setType("RETRIEVAL");
        baseline.setName("baseline");
        baseline.setVersion("v0");
        baseline.setConfigSnapshot(JsonUtil.toJsonStr(RetrievalRuleSpec.baseline()));
        return baseline;
    }

    /**
     * 查询向量化：跟随集合 EMBED 策略（模型 + queryTemplate）；网关失败 → null（该通道降级为空）。
     *
     * <p>策略版本行配置快照缺失或损坏（解析抛异常）→ 同样返回 null：向量通道降级为空，不构造默认策略。
     */
    private List<Float> embedQuery(WbIndexVersion version, String query) {
        ComboSnapshot combo = JsonUtil.toObject(version.getComboSnapshot(), ComboSnapshot.class);
        if (NullUtil.isNull(combo) || !combo.hasCompleteStageStrategies()) {
            log.warn("===> SearchServiceImpl 查询向量化中止：组合快照缺失环节策略维度, versionId={}", version.getId());
            return null;
        }
        WbPipelineStrategyVersion row = strategyVersionDbService.getByTypeAndNameAndVersion(
                "EMBED", strategyNameOf(combo.getEmbedStrategy()), strategyVersionOf(combo.getEmbedStrategy()));
        if (NullUtil.isNull(row)) {
            log.warn("===> SearchServiceImpl 查询向量化中止：EMBED 策略版本行不存在, strategy={}",
                    combo.getEmbedStrategy());
            return null;
        }
        EmbedStrategy strategy;
        try {
            strategy = embedStrategyParser.parse(row.getConfigSnapshot());
        } catch (Exception e) {
            log.warn("===> SearchServiceImpl 查询向量化中止：EMBED 策略版本行配置快照缺失或损坏, versionId={}, strategy={}",
                    version.getId(), combo.getEmbedStrategy(), e);
            return null;
        }
        String template = StrUtil.isBlank(strategy.getQueryTemplate()) ? "{query}" : strategy.getQueryTemplate();
        String input = template.replace("{query}", query);
        // 模型调用层已移除：查询向量化不可用时走既有降级分支（返回 null，检索降级）。
        //  接入模型模块后在此按 strategy.getModel() 编码 input，取返回向量的第一个作为查询向量。
        log.warn("===> SearchServiceImpl 查询向量化跳过：模型调用层待接入, versionId={}, input长度={}",
                version.getId(), input.length());
        return null;
    }

    private String strategyNameOf(String nameVersion) {
        int split = nameVersion.lastIndexOf('-');
        return split > 0 ? nameVersion.substring(0, split) : nameVersion;
    }

    private String strategyVersionOf(String nameVersion) {
        int split = nameVersion.lastIndexOf('-');
        return split > 0 ? nameVersion.substring(split + 1) : "";
    }

    /** 融合/直通：HYBRID → RRF；单通道 → 原通道顺序（分数：向量=相似度，全文=null） */
    private List<SearchHitVO> orderHits(RetrievalRuleSpec spec, List<FullTextHit> fullTextHits,
                                        List<VectorHit> vectorHits) {
        if (RetrievalRuleSpec.CHANNEL_HYBRID.equals(spec.getChannel())) {
            Map<String, VectorHit> vectorByChunk = vectorHits.stream()
                    .collect(Collectors.toMap(VectorHit::getChunkId, Function.identity(), (a, b) -> a));
            Map<String, FullTextHit> fullTextByChunk = fullTextHits.stream()
                    .collect(Collectors.toMap(FullTextHit::getChunkId, Function.identity(), (a, b) -> a));
            List<RrfFusion.FusedHit> fused = RrfFusion.fuse(
                    List.of(fullTextHits.stream().map(FullTextHit::getChunkId).toList(),
                            vectorHits.stream().map(VectorHit::getChunkId).toList()),
                    spec.getFusion().getRrfK());
            List<SearchHitVO> ordered = new ArrayList<>(fused.size());
            for (RrfFusion.FusedHit fusedHit : fused) {
                VectorHit vectorHit = vectorByChunk.get(fusedHit.chunkId());
                if (NullUtil.isNotNull(vectorHit)) {
                    ordered.add(toVO(vectorHit, fusedHit.score()));
                } else {
                    ordered.add(toVO(fullTextByChunk.get(fusedHit.chunkId()), fusedHit.score()));
                }
            }
            return ordered;
        }
        if (RetrievalRuleSpec.CHANNEL_FULLTEXT.equals(spec.getChannel())) {
            return fullTextHits.stream().map(hit -> toVO(hit, null)).toList();
        }
        return vectorHits.stream()
                .map(hit -> toVO(hit, NullUtil.isNull(hit.getScore()) ? null : hit.getScore().doubleValue()))
                .toList();
    }

    /** 父片展开：集合内批量取父片行 → 父片全文替换、isParent=true；按父片去重保留最高分；缺父片保留子片 */
    private List<SearchHitVO> expandParent(String collectionName, List<SearchHitVO> ordered) {
        List<String> parentIds = ordered.stream()
                .map(SearchHitVO::getParentChunkId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        if (parentIds.isEmpty()) {
            return ordered;
        }
        Map<String, IndexRow> parentRows = milvusIndexPort.queryChunkByIds(collectionName, parentIds).stream()
                .collect(Collectors.toMap(IndexRow::getChunkId, Function.identity(), (a, b) -> a));
        Map<String, SearchHitVO> deduped = new LinkedHashMap<>();
        for (SearchHitVO hit : ordered) {
            IndexRow parent = StrUtil.isBlank(hit.getParentChunkId()) ? null : parentRows.get(hit.getParentChunkId());
            String dedupeKey = NullUtil.isNull(parent) ? hit.getChunkId() : parent.getChunkId();
            if (deduped.containsKey(dedupeKey)) {
                // 同父片多子片命中 → 保留最高融合分（已按序遍历）
                continue;
            }
            if (NullUtil.isNotNull(parent)) {
                hit.setContent(parent.getContent());
                hit.setTitlePath(parent.getTitlePath());
                hit.setChunkId(parent.getChunkId());
                hit.setDocumentId(parent.getDocumentId());
                hit.setParentChunkId(parent.getParentChunkId());
                // 溯源取父片的「整段」：父片展开后返回的是整段全文，溯源也应指向整段；
                // 子片溯源只是命中的那一小片，与返回的整段内容不匹配
                hit.setSourceElementIds(parent.getSourceElementIds());
                hit.setIsParent(true);
            }
            deduped.put(dedupeKey, hit);
        }
        return new ArrayList<>(deduped.values());
    }

    /** 命中 → VO（向量/全文两路命中字段口径一致，统一按 IndexHit 契约读） */
    private SearchHitVO toVO(IndexHit hit, Double score) {
        return toVO(hit.getChunkId(), hit.getContent(), hit.getTitlePath(), hit.getSourceElementIds(),
                hit.getDocumentId(), hit.getContentType(), hit.getParentChunkId(), score);
    }

    /** 命中字段原样入 VO（命中直取不回查库） */
    private SearchHitVO toVO(String chunkId, String content, String titlePath, String sourceElementIds,
                             Long documentId, String contentType, String parentChunkId, Double score) {
        SearchHitVO vo = new SearchHitVO();
        vo.setChunkId(chunkId);
        vo.setContent(content);
        vo.setTitlePath(titlePath);
        vo.setSourceElementIds(sourceElementIds);
        vo.setDocumentId(documentId);
        vo.setContentType(contentType);
        vo.setParentChunkId(parentChunkId);
        vo.setScore(score);
        vo.setIsParent(false);
        return vo;
    }
}
