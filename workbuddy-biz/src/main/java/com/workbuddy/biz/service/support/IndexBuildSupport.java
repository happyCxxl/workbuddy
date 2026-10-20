package com.workbuddy.biz.service.support;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.ChunkControlService;
import com.workbuddy.biz.service.PreprocessControlService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.dto.response.index.IndexValidateItemVO;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.FullTextQuery;
import com.workbuddy.worker.indexing.search.VectorHit;
import com.workbuddy.worker.indexing.search.VectorQuery;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 索引构建技术支撑：批次对账、评测冻结集追加、候选校验与文件拓扑补齐。
 * 构建编排只负责触发与状态流转，重复的集合写入/对账/补齐口径集中在此。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexBuildSupport {

    private final IndexVersionStateSupport stateSupport;
    private final IndexComboReconciler indexComboReconciler;
    private final MilvusIndexPort milvusIndexPort;
    private final WbIndexSetDbService indexSetDbService;
    private final WbIndexVersionDbService indexVersionDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbPipelineStrategyVersionDbService strategyVersionDbService;
    private final PreprocessControlService preprocessControlService;
    private final ChunkControlService chunkControlService;
    private final WbPipelineProductDbService productDbService;
    private final IndexLineageResolver lineageResolver;

    /**
     * 校验版本：仅就绪/在线/已退役可验证；一致性对账 + 向量冒烟 + 全文冒烟。
     *
     * @param versionId 版本 ID
     * @return 校验结果
     */
    public IndexValidateVO validate(Long versionId) {
        WbIndexVersion version = stateSupport.requireVersion(versionId);
        WbIndexSet set = stateSupport.requireSetOf(version);
        ThrowUtil.throwIf(!IndexVersionStatus.READY.name().equals(version.getStatus())
                        && !IndexVersionStatus.ONLINE.name().equals(version.getStatus())
                        && !IndexVersionStatus.RETIRED.name().equals(version.getStatus()),
                ErrorCode.INDEX_BUILDING_CONFLICT, "仅就绪/在线/已退役版本可验证");
        ComboSnapshot combo = stateSupport.requireCombo(version);
        Long kbId = set.getKnowledgeBaseId();
        IndexComboReconciler.ComboExpectation expectation = indexComboReconciler.computeExpected(kbId, combo);
        IndexValidateVO vo = new IndexValidateVO();
        List<IndexValidateItemVO> items = new ArrayList<>();
        String collectionName = MilvusIndexPort.collectionName(kbId, version.getVersionNo());
        boolean consistent = false;
        if (expectation.complete() && expectation.dimConsistent()) {
            List<String> actual = milvusIndexPort.listChunkIds(collectionName);
            consistent = expectation.chunkIds().equals(new HashSet<>(actual));
            items.add(new IndexValidateItemVO("CONSISTENCY", consistent,
                    "产物 " + expectation.chunkIds().size() + " 片 vs Milvus " + actual.size() + " 片"));
        } else {
            items.add(new IndexValidateItemVO("CONSISTENCY", false,
                    "组合产物不完整: " + (expectation.complete() ? expectation.dimError() : expectation.gap())));
        }
        boolean vectorPassed = false;
        String vectorDetail = "无可用样本向量";
        if (consistent && NullUtil.isNotNull(expectation.sampleVector())) {
            List<VectorHit> vectorHits = milvusIndexPort.searchVector(collectionName,
                    VectorQuery.builder().vector(expectation.sampleVector()).topK(5).build());
            vectorPassed = !vectorHits.isEmpty();
            vectorDetail = "命中 " + vectorHits.size() + " 条";
        }
        items.add(new IndexValidateItemVO("VECTOR_SMOKE", vectorPassed, vectorDetail));
        boolean textPassed = false;
        String textDetail = "无可用样本文本";
        if (consistent && StrUtil.isNotBlank(expectation.sampleKeyword())) {
            List<FullTextHit> textHits = milvusIndexPort.searchFullText(collectionName,
                    FullTextQuery.builder().keyword(expectation.sampleKeyword()).limit(5).build());
            textPassed = !textHits.isEmpty();
            textDetail = "命中 " + textHits.size() + " 条";
        }
        items.add(new IndexValidateItemVO("FULLTEXT_SMOKE", textPassed, textDetail));
        vo.setItems(items);
        vo.setPassed(consistent && vectorPassed && textPassed);
        return vo;
    }

    /**
     * 批次对账：本文件产物 chunkId 集合 == 集合内本文件 chunkId 集合（不一致告警）。
     *
     * @param collectionName 集合名
     * @param fileResultId   文件结果 ID
     * @param rows           本文件追加行
     */
    public void reconcileFile(String collectionName, Long fileResultId, List<IndexRow> rows) {
        Set<String> expected = rows.stream().map(IndexRow::getChunkId).collect(Collectors.toSet());
        List<String> actual = milvusIndexPort.listChunkIds(collectionName, fileResultId);
        if (!expected.equals(new HashSet<>(actual))) {
            log.warn("===> IndexBuildSupport 批次对账不一致, collection={}, fileResultId={}, expected={}, actual={}",
                    collectionName, fileResultId, expected.size(), actual.size());
        }
    }

    /**
     * 评测冻结集路由：三元组匹配且范围包含该文件的活跃 LIST 版本 → 幂等追加。
     *
     * @param kbId         知识库 ID
     * @param fileResultId 文件结果 ID
     * @param productCombo 产物组合
     * @param rows         本文件追加行
     */
    public void appendToFrozenScopes(Long kbId, Long fileResultId, ComboSnapshot productCombo,
                                     List<IndexRow> rows) {
        WbIndexSet set = indexSetDbService.getByKb(kbId);
        if (NullUtil.isNull(set)) {
            return;
        }
        for (WbIndexVersion version : indexVersionDbService.listByIndexSetId(set.getId())) {
            if (!Set.of(IndexVersionStatus.CREATED.name(), IndexVersionStatus.BUILDING.name(),
                    IndexVersionStatus.READY.name(), IndexVersionStatus.FAILED.name())
                    .contains(version.getStatus())) {
                continue;
            }
            ComboSnapshot combo = JsonUtil.toObject(version.getComboSnapshot(), ComboSnapshot.class);
            if (NullUtil.isNull(combo) || !combo.isListScope()
                    || NullUtil.isNull(combo.getFileResultIds())
                    || !combo.getFileResultIds().contains(fileResultId)) {
                continue;
            }
            if (!Objects.equals(combo.getShape(), productCombo.getShape())
                    || !Objects.equals(combo.getStageStrategies(), productCombo.getStageStrategies())) {
                continue;
            }
            String collectionName = MilvusIndexPort.collectionName(kbId, version.getVersionNo());
            try {
                milvusIndexPort.append(collectionName, rows);
                log.info("===> IndexBuildSupport 评测冻结集追加, collection={}, fileResultId={}",
                        collectionName, fileResultId);
            } catch (Exception e) {
                log.warn("===> IndexBuildSupport 评测冻结集追加失败（集合未就绪？重触发构建可补齐）, "
                        + "collection={}, fileResultId={}", collectionName, fileResultId, e);
            }
        }
    }

    /**
     * 拓扑重放补齐（单文件）：缺预处理产物 → 投递预处理（目标预处理策略）；
     * 有预处理缺切片 → 投递切片（目标切片策略 + 显式 upstreamProductId）；
     * 有切片缺向量 → 提示手动（逐环节口径不变）。
     *
     * @param fileResultId 文件结果 ID
     * @param target       目标组合
     */
    public void backfillFile(Long fileResultId, ComboSnapshot target) {
        String targetPreprocess = target.getPreprocessStrategy();
        List<WbChunkSet> chunkSets = chunkSetDbService.listByFileResultIds(List.of(fileResultId));
        boolean preprocessOk = chunkSets.stream().anyMatch(s ->
                targetPreprocess.equals(lineageResolver.resolvePreprocessStrategy(s.getUpstreamProductId())));
        if (!preprocessOk) {
            dispatchPreprocess(fileResultId, targetPreprocess);
            return;
        }
        boolean chunkOk = chunkSets.stream().anyMatch(s ->
                target.getChunkStrategy().equals(s.getChunkStrategyVersion())
                        && targetPreprocess.equals(
                                lineageResolver.resolvePreprocessStrategy(s.getUpstreamProductId())));
        if (!chunkOk) {
            dispatchChunk(fileResultId, target);
            return;
        }
        log.info("===> IndexBuildSupport 补齐提示：文件已具备目标组合切片产物，向量化需手动触发, "
                + "fileResultId={}, chunkStrategy={}", fileResultId, target.getChunkStrategy());
    }

    private void dispatchPreprocess(Long fileResultId, String targetPreprocess) {
        WbPipelineStrategyVersion strategyRow = strategyRowOf(PipelineStage.PREPROCESS.name(), targetPreprocess);
        if (NullUtil.isNull(strategyRow)) {
            log.warn("===> IndexBuildSupport 补齐中止：目标预处理策略不存在, strategy={}", targetPreprocess);
            return;
        }
        try {
            preprocessControlService.preprocess(fileResultId, strategyRow.getId(), null);
            log.info("===> IndexBuildSupport 补齐投递预处理, fileResultId={}, strategy={}",
                    fileResultId, targetPreprocess);
        } catch (Exception e) {
            log.warn("===> IndexBuildSupport 补齐预处理投递失败, fileResultId={}", fileResultId, e);
        }
    }

    private void dispatchChunk(Long fileResultId, ComboSnapshot target) {
        String targetPreprocess = target.getPreprocessStrategy();
        WbPipelineStrategyVersion strategyRow = strategyRowOf(PipelineStage.CHUNK.name(), target.getChunkStrategy());
        if (NullUtil.isNull(strategyRow)) {
            log.warn("===> IndexBuildSupport 补齐中止：目标切片策略不存在, strategy={}", target.getChunkStrategy());
            return;
        }
        Long upstreamProductId = preprocessProductIdOf(fileResultId, targetPreprocess);
        if (NullUtil.isNull(upstreamProductId)) {
            log.warn("===> IndexBuildSupport 补齐中止：匹配预处理产物不存在, fileResultId={}, strategy={}",
                    fileResultId, targetPreprocess);
            return;
        }
        try {
            chunkControlService.chunk(fileResultId, strategyRow.getId(), upstreamProductId);
            log.info("===> IndexBuildSupport 补齐投递切片（显式绑定上游预处理产物）, "
                    + "fileResultId={}, strategy={}", fileResultId, target.getChunkStrategy());
        } catch (Exception e) {
            log.warn("===> IndexBuildSupport 补齐切片投递失败, fileResultId={}", fileResultId, e);
        }
    }

    /** 策略 name-version → 策略版本行（无 → null） */
    private WbPipelineStrategyVersion strategyRowOf(String type, String nameVersion) {
        if (StrUtil.isBlank(nameVersion)) {
            return null;
        }
        int split = nameVersion.lastIndexOf('-');
        String name = split > 0 ? nameVersion.substring(0, split) : nameVersion;
        String versionNo = split > 0 ? nameVersion.substring(split + 1) : "";
        return strategyVersionDbService.getByTypeAndNameAndVersion(type, name, versionNo);
    }

    /** 匹配目标预处理策略的预处理产物 ID（capabilitySnapshot 解析比对；无 → null） */
    private Long preprocessProductIdOf(Long fileResultId, String targetPreprocess) {
        for (WbPipelineProduct product : productDbService.listByFileResultId(fileResultId)) {
            if (!PipelineStage.PREPROCESS.name().equals(product.getStage())) {
                continue;
            }
            try {
                PreprocessStrategy strategy =
                        JsonUtil.toObject(product.getCapabilitySnapshot(), PreprocessStrategy.class);
                if (NullUtil.isNotNull(strategy) && targetPreprocess.equals(strategy.fullVersion())) {
                    return product.getId();
                }
            } catch (Exception e) {
                log.warn("===> IndexBuildSupport 预处理产物快照解析失败, productId={}", product.getId(), e);
            }
        }
        return null;
    }
}
