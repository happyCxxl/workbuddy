package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.ChunkControlService;
import com.workbuddy.biz.service.IndexComboService;
import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.biz.service.PreprocessControlService;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.IndexComboReconciler;
import com.workbuddy.biz.service.support.IndexLineageResolver;
import com.workbuddy.biz.service.support.IndexRowAssembler;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.dto.response.index.IndexValidateItemVO;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.common.enums.index.IndexBuildTrigger;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.enums.knowledge.StrategyBindingSwitch;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.BuildOrder;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.FullTextQuery;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import com.workbuddy.worker.indexing.search.VectorHit;
import com.workbuddy.worker.indexing.search.VectorQuery;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 索引构建与发布控制面实现（口径见 {@link IndexSetService}）。
 * 发布/回退为单事务三级指针（版本 publishedAt/By + 集合二级指针 + 知识库一级指针首次置位）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexSetServiceImpl implements IndexSetService {

    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.INDEX_VERSION.key();

    private final WbKnowledgeBaseDbService knowledgeBaseDbService;
    private final WbIndexSetDbService indexSetDbService;
    private final WbIndexVersionDbService indexVersionDbService;
    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final WbFileResultDbService fileResultDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbEmbeddingSetDbService embeddingSetDbService;
    private final WbPipelineStrategyVersionDbService strategyVersionDbService;
    private final WbAuditLogDbService kbAuditLogDbService;
    private final IndexComboService indexComboService;
    private final ChunkControlService chunkControlService;
    private final PreprocessControlService preprocessControlService;
    private final WbPipelineProductDbService productDbService;
    private final IndexLineageResolver lineageResolver;
    private final IndexRowAssembler indexRowAssembler;
    private final IndexComboReconciler indexComboReconciler;
    private final MilvusIndexPort milvusIndexPort;
    private final TaskQueueSupport taskQueue;
    private final TransactionTemplate transactionTemplate;
    /** 自身引用提供者（发布经代理调用保证事务生效；ObjectProvider 惰性解析，解除 Bean 自引用循环） */
    private final ObjectProvider<IndexSetService> self;

    /**
     * 文件产物就绪后的自动注册钩子：绑定开启时按最新产物登记组合。
     *
     * @param fileResultId 文件结果 ID
     */
    @Override
    public void onFileProductsReady(Long fileResultId) {
        WbFileResult file = fileResultDbService.getById(fileResultId);
        if (NullUtil.isNull(file)) {
            log.warn("===> IndexSetServiceImpl 文件产物就绪回调但文件结果不存在, fileResultId={}", fileResultId);
            return;
        }
        Long kbId = file.getKnowledgeBaseId();

        // ① 血缘解析：文件最新产物的组合（规则一：追加目标按产物实际组合决定，不按当前在线/绑定）
        ProductCombo product = resolveProductCombo(fileResultId);
        if (NullUtil.isNull(product)) {
            log.info("===> IndexSetServiceImpl 产物血统不完整，跳过追加, fileResultId={}", fileResultId);
            return;
        }

        // ② 定位/复用组合集合（绑定关=评测模式不自动注册新组合；无活跃版本时跳过追加，
        //    规则三补齐与冻结集路由照常执行）
        WbIndexVersion version = registerComboCollection(kbId, product.combo(), product.dimension());
        List<IndexRow> rows = indexRowAssembler.assemble(fileResultId,
                StrUtil.blankToDefault(file.getOwner(), ""), product.chunkRow(), product.embedRow());
        if (NullUtil.isNotNull(version)) {
            if (NullUtil.isNull(rows)) {
                markFailed(version, "文件 " + fileResultId + " 向量产物读取失败");
                return;
            }
            if (rows.isEmpty()) {
                log.info("===> IndexSetServiceImpl 无可写记录（SUCCESS/CACHED 子集为空），跳过追加, fileResultId={}",
                        fileResultId);
                return;
            }
            String collectionName = MilvusIndexPort.collectionName(kbId, version.getVersionNo());
            milvusIndexPort.append(collectionName, rows);
            updateLedger(version, rows);

            // ③ 批次对账（本文件：产物 chunkId == 集合内本文件 chunkId；不一致告警，升级为处置）
            reconcileFile(collectionName, fileResultId, rows);

            // ④ 发布判定：绑定开且产物组合==绑定组合 → READY + 自动发布（文件即版本：新文件即上线）
            maybeAutoPublish(kbId, version, product.combo());
        }

        // ⑤ 规则三：产物组合 ≠ 在线组合 → 按在线组合全链补齐该文件（文件永不丢）
        maybeBackfillToOnline(kbId, fileResultId, product.combo());

        // ⑥ 评测冻结集路由：范围内文件的活跃 LIST 版本同步追加（冻结语义：只认 fileResultIds）
        if (NullUtil.isNotNull(rows) && !rows.isEmpty()) {
            appendToFrozenScopes(kbId, fileResultId, product.combo(), rows);
        }
    }

    /** 文件产物组合（血缘 + 维度 + 产物行） */
    private record ProductCombo(ComboSnapshot combo, int dimension, WbChunkSet chunkRow, WbEmbeddingSet embedRow) {
    }

    /** 血缘解析：最新 EMBED 行（EMBED 策略）→ 其 chunkSetRef 切片行（CHUNK 策略 + 上游链预处理策略）→ 组合 */
    private ProductCombo resolveProductCombo(Long fileResultId) {
        List<WbEmbeddingSet> embedSets = embeddingSetDbService.listByFileResultIds(List.of(fileResultId));
        WbEmbeddingSet embedRow = embedSets.stream().max(Comparator.comparing(WbEmbeddingSet::getId)).orElse(null);
        if (NullUtil.isNull(embedRow)) {
            return null;
        }
        WbChunkSet chunkRow = NullUtil.isNull(embedRow.getChunkSetRef())
                ? null : chunkSetDbService.getById(embedRow.getChunkSetRef());
        if (NullUtil.isNull(chunkRow)) {
            return null;
        }
        String preprocess = lineageResolver.resolvePreprocessStrategy(chunkRow.getUpstreamProductId());
        if (StrUtil.hasBlank(preprocess, chunkRow.getChunkStrategyVersion(), embedRow.getStrategyVersion())) {
            return null;
        }
        return new ProductCombo(
                ComboSnapshot.of(preprocess, chunkRow.getChunkStrategyVersion(), embedRow.getStrategyVersion()),
                ObjectUtil.defaultIfNull(embedRow.getDimension(), 0), chunkRow, embedRow);
    }

    /**
     * 定位/复用组合集合：同组合活跃版本直接复用（追加目标）；
     * 无活跃版本时——绑定开（生产就绪）自动注册版本行 + 建集合 + 预热 load；
     * 绑定关（评测模式）不自动注册（评测索引构建走手动 buildCandidate），返回 null。
     */
    private WbIndexVersion registerComboCollection(Long kbId, ComboSnapshot combo, int dimension) {
        WbIndexSet set = indexSetDbService.getOrCreateByKb(kbId);
        String comboJson = JsonUtil.toJsonStr(combo);
        WbIndexVersion active = findActiveVersion(set.getId(), comboJson);
        if (NullUtil.isNotNull(active)) {
            return active;
        }
        if (bindingDisabled(kbId)) {
            log.info("===> IndexSetServiceImpl 评测模式（策略绑定关）跳过组合自动注册, kbId={}, combo={}",
                    kbId, comboJson);
            return null;
        }
        WbIndexVersion version = new WbIndexVersion();
        version.setIndexSetId(set.getId());
        version.setVersionNo(indexVersionDbService.nextVersionNo(set.getId()));
        version.setComboSnapshot(comboJson);
        version.setStatus(IndexVersionStatus.CREATED.name());
        version.setChunkCount(0);
        version.setVectorCount(0);
        try {
            indexVersionDbService.save(version);
        } catch (DuplicateKeyException e) {
            // uk_set_version 并发兜底：版本号冲突重取一次
            version.setVersionNo(indexVersionDbService.nextVersionNo(set.getId()));
            indexVersionDbService.save(version);
        }
        String collectionName = MilvusIndexPort.collectionName(kbId, version.getVersionNo());
        try {
            milvusIndexPort.ensureCollection(collectionName, dimension);
            milvusIndexPort.load(collectionName);
        } catch (Exception e) {
            markFailed(version, "集合初始化失败: " + StrUtil.maxLength(String.valueOf(e.getMessage()), 1000));
            return null;
        }
        log.info("===> IndexSetServiceImpl 组合已注册, kbId={}, versionNo={}, combo={}",
                kbId, version.getVersionNo(), comboJson);
        return version;
    }

    /** 活账本：统计随追加持续更新（追加是常态动作，不流转状态） */
    private void updateLedger(WbIndexVersion version, List<IndexRow> rows) {
        version.setChunkCount(ObjectUtil.defaultIfNull(version.getChunkCount(), 0) + rows.size());
        int vectors = (int) rows.stream()
                .filter(r -> NullUtil.isNotNull(r.getVector()) && !r.getVector().isEmpty()).count();
        version.setVectorCount(ObjectUtil.defaultIfNull(version.getVectorCount(), 0) + vectors);
        version.setBuildError(null);
        indexVersionDbService.updateById(version);
    }

    /** 批次对账：本文件产物 chunkId 集合 == 集合内本文件 chunkId 集合（不一致告警，升级为处置） */
    private void reconcileFile(String collectionName, Long fileResultId, List<IndexRow> rows) {
        Set<String> expected = rows.stream().map(IndexRow::getChunkId).collect(Collectors.toSet());
        List<String> actual = milvusIndexPort.listChunkIds(collectionName, fileResultId);
        if (!expected.equals(new HashSet<>(actual))) {
            log.warn("===> IndexSetServiceImpl 批次对账不一致, collection={}, fileResultId={}, expected={}, actual={}",
                    collectionName, fileResultId, expected.size(), actual.size());
        }
    }

    /** 追加后处置：批次对账通过 → 版本 READY（活账本）；绑定开且产物组合==绑定组合 → 自动发布 */
    private void maybeAutoPublish(Long kbId, WbIndexVersion version, ComboSnapshot combo) {
        if (combo.isListScope()) {
            // 评测冻结集永不自动发布（发布/回退亦拒 40449）
            return;
        }
        String status = version.getStatus();
        if (IndexVersionStatus.CREATED.name().equals(status) || IndexVersionStatus.BUILDING.name().equals(status)) {
            version.setStatus(IndexVersionStatus.READY.name());
            version.setValidatedAt(LocalDateTime.now());
            indexVersionDbService.updateById(version);
        }
        if (bindingDisabled(kbId) || !IndexVersionStatus.READY.name().equals(version.getStatus())) {
            return;
        }
        ComboSnapshot bound = indexComboService.resolveBoundCombo(kbId);
        if (NullUtil.isNull(bound) || !comboEquals(bound, combo)) {
            return;
        }
        try {
            self.getObject().publish(version.getId());
        } catch (Exception e) {
            log.warn("===> IndexSetServiceImpl 自动发布失败，停留 READY 等管理员, versionId={}", version.getId(), e);
        }
    }

    /**
     * 评测冻结集路由：三元组匹配且范围包含该文件的活跃 LIST 版本 → 幂等追加。
     * 冻结语义：只认 fileResultIds 列表，列表外文件永不进；不逐条更账本（READY 对账统一收敛）。
     * 追加失败不致命：集合可能尚未就绪（构建任务并发窗口），warn 记录，重触发构建回填可补齐。
     */
    private void appendToFrozenScopes(Long kbId, Long fileResultId, ComboSnapshot productCombo,
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
                log.info("===> IndexSetServiceImpl 评测冻结集追加, collection={}, fileResultId={}",
                        collectionName, fileResultId);
            } catch (Exception e) {
                log.warn("===> IndexSetServiceImpl 评测冻结集追加失败（集合未就绪？重触发构建可补齐）, collection={}, fileResultId={}",
                        collectionName, fileResultId, e);
            }
        }
    }

    /** 版本行组合快照读取唯一入口（业务路径）：反序列化 + 格式断言。
     * 缺少 stageStrategies 维度的快照在此拒绝（40448 需废弃重灌），下游不再散点防御；
     * 展示路径（toVersionVO）不走此处，保持宽松可读。
     */
    private ComboSnapshot requireVersionCombo(WbIndexVersion version) {
        ComboSnapshot combo = JsonUtil.toObject(version.getComboSnapshot(), ComboSnapshot.class);
        ThrowUtil.throwIf(NullUtil.isNull(combo), ErrorCode.PARAM_INVALID, "版本组合快照缺失");
        combo.requireStageStrategies();
        return combo;
    }

    /** 按 id 取版本行（不存在 40441）：发布/回退/验证/回收共用入口校验 */
    private WbIndexVersion requireVersion(Long versionId) {
        WbIndexVersion version = indexVersionDbService.getById(versionId);
        ThrowUtil.throwIf(NullUtil.isNull(version), ErrorCode.INDEX_VERSION_NOT_FOUND);
        return version;
    }

    /** 版本行所属索引集合（缺失同样按 40441 处理，与版本不存在对外不可区分） */
    private WbIndexSet requireSetOf(WbIndexVersion version) {
        WbIndexSet set = indexSetDbService.getById(version.getIndexSetId());
        ThrowUtil.throwIf(NullUtil.isNull(set), ErrorCode.INDEX_VERSION_NOT_FOUND);
        return set;
    }

    /** 规则三：产物组合 ≠ 在线组合 → 按在线组合全链补齐该文件（从缺失的最上游环节投递） */
    private void maybeBackfillToOnline(Long kbId, Long fileResultId, ComboSnapshot productCombo) {
        WbIndexVersion online = currentPublished(kbId);
        if (NullUtil.isNull(online)) {
            return;
        }
        ComboSnapshot onlineCombo = requireVersionCombo(online);
        if (comboEquals(onlineCombo, productCombo)) {
            return;
        }
        backfillFile(fileResultId, onlineCombo);
    }

    /**
     * 拓扑重放补齐（单文件）：缺预处理产物 → 投递预处理（目标预处理策略）；
     * 有预处理缺切片 → 投递切片（目标切片策略 + 显式 upstreamProductId=匹配预处理产物，根治混合口径）；
     * 有切片缺向量 → 提示手动（逐环节口径不变）。投递幂等由各环节执行键查重（已有任务一律拒绝 40431）保证。
     */
    private void backfillFile(Long fileResultId, ComboSnapshot target) {
        String targetPreprocess = target.getPreprocessStrategy();
        List<WbChunkSet> chunkSets = chunkSetDbService.listByFileResultIds(List.of(fileResultId));
        boolean preprocessOk = chunkSets.stream().anyMatch(s ->
                targetPreprocess.equals(lineageResolver.resolvePreprocessStrategy(s.getUpstreamProductId())));
        if (!preprocessOk) {
            WbPipelineStrategyVersion strategyRow = strategyRowOf(PipelineStage.PREPROCESS.name(), targetPreprocess);
            if (NullUtil.isNull(strategyRow)) {
                log.warn("===> IndexSetServiceImpl 补齐中止：目标预处理策略不存在, strategy={}", targetPreprocess);
                return;
            }
            try {
                preprocessControlService.preprocess(fileResultId, strategyRow.getId(), null);
                log.info("===> IndexSetServiceImpl 补齐投递预处理, fileResultId={}, strategy={}",
                        fileResultId, targetPreprocess);
            } catch (Exception e) {
                log.warn("===> IndexSetServiceImpl 补齐预处理投递失败, fileResultId={}", fileResultId, e);
            }
            return;
        }
        boolean chunkOk = chunkSets.stream().anyMatch(s ->
                target.getChunkStrategy().equals(s.getChunkStrategyVersion())
                        && targetPreprocess.equals(
                                lineageResolver.resolvePreprocessStrategy(s.getUpstreamProductId())));
        if (!chunkOk) {
            WbPipelineStrategyVersion strategyRow =
                    strategyRowOf(PipelineStage.CHUNK.name(), target.getChunkStrategy());
            if (NullUtil.isNull(strategyRow)) {
                log.warn("===> IndexSetServiceImpl 补齐中止：目标切片策略不存在, strategy={}", target.getChunkStrategy());
                return;
            }
            Long upstreamProductId = preprocessProductIdOf(fileResultId, targetPreprocess);
            if (NullUtil.isNull(upstreamProductId)) {
                log.warn("===> IndexSetServiceImpl 补齐中止：匹配预处理产物不存在, fileResultId={}, strategy={}",
                        fileResultId, targetPreprocess);
                return;
            }
            try {
                chunkControlService.chunk(fileResultId, strategyRow.getId(), upstreamProductId);
                log.info("===> IndexSetServiceImpl 补齐投递切片（显式绑定上游预处理产物）, fileResultId={}, strategy={}",
                        fileResultId, target.getChunkStrategy());
            } catch (Exception e) {
                log.warn("===> IndexSetServiceImpl 补齐切片投递失败, fileResultId={}", fileResultId, e);
            }
            return;
        }
        log.info("===> IndexSetServiceImpl 补齐提示：文件已具备目标组合切片产物，向量化需手动触发, fileResultId={}, chunkStrategy={}",
                fileResultId, target.getChunkStrategy());
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
                log.warn("===> IndexSetServiceImpl 预处理产物快照解析失败, productId={}", product.getId(), e);
            }
        }
        return null;
    }

    /** 组合全等判定（范围 × 形态 × stageStrategies；任一维变化 = 新组合 = 新集合，含范围维度） */
    private boolean comboEquals(ComboSnapshot a, ComboSnapshot b) {
        return Objects.equals(a.getShape(), b.getShape())
                && Objects.equals(a.getFileScopeMode(), b.getFileScopeMode())
                && Objects.equals(a.getFileResultIds(), b.getFileResultIds())
                && Objects.equals(a.getStageStrategies(), b.getStageStrategies());
    }

    /**
     * 手动构建候选索引：校验范围与策略 → 登记组合 → 投递 BUILD_INDEX 任务。
     *
     * @param order 构建命令
     * @return 任务 ID 与生效策略
     */
    @Override
    public IndexBuildTriggerVO buildCandidate(BuildOrder order) {
        ThrowUtil.throwIf(NullUtil.isNull(order) || NullUtil.isNull(order.getKnowledgeBaseId())
                        || NullUtil.isNull(order.getComboSnapshot()),
                ErrorCode.PARAM_INVALID, "构建命令缺失");
        Long kbId = order.getKnowledgeBaseId();
        ThrowUtil.throwIf(NullUtil.isNull(knowledgeBaseDbService.getById(kbId)), ErrorCode.KB_NOT_FOUND);
        ComboSnapshot combo = order.getComboSnapshot();
        // 范围口径：LIST = 评测冻结集（文件列表非空且全属本 KB）；ALL = 范围字段归一化
        if (combo.isListScope()) {
            List<Long> fileIds = combo.getFileResultIds();
            ThrowUtil.throwIf(NullUtil.isNull(fileIds) || fileIds.isEmpty(),
                    ErrorCode.PARAM_INVALID, "指定文件构建（LIST）需提供文件列表");
            Set<Long> kbFileIds = fileResultDbService.listByKb(kbId).stream()
                    .map(WbFileResult::getId).collect(Collectors.toSet());
            List<Long> foreign = fileIds.stream().filter(id -> !kbFileIds.contains(id)).toList();
            ThrowUtil.throwIf(!foreign.isEmpty(), ErrorCode.PARAM_INVALID, "文件列表包含非本知识库文件: " + foreign);
        } else {
            combo.setFileScopeMode("ALL");
            combo.setFileResultIds(null);
        }
        // 绑定开=按 KB 绑定策略集合：前端只传数据范围/形态时由后端解析绑定组合补齐策略口径
        if (!combo.hasCompleteStageStrategies()) {
            ComboSnapshot bound = indexComboService.resolveBoundCombo(kbId);
            ThrowUtil.throwIf(NullUtil.isNull(bound), ErrorCode.INDEX_COMBO_INCOMPLETE,
                    "知识库绑定策略不齐全，无法按绑定策略构建");
            combo.setStageStrategies(bound.getStageStrategies());
        }
        // 环节白名单防线：stageStrategies 只允许预处理/切片/向量三环节（未知键=坏数据，拒绝而非静默携带）
        Set<String> allowedStages = Set.of(PipelineStage.PREPROCESS.name(),
                PipelineStage.CHUNK.name(), PipelineStage.EMBED.name());
        List<String> unknownStages = combo.getStageStrategies().keySet().stream()
                .filter(stage -> !allowedStages.contains(stage))
                .toList();
        ThrowUtil.throwIf(!unknownStages.isEmpty(), ErrorCode.PARAM_INVALID,
                "组合快照含未知环节策略: " + unknownStages);
        String trigger = StrUtil.blankToDefault(order.getTrigger(), IndexBuildTrigger.REBUILD.name());
        return tryBuild(kbId, combo, trigger, true);
    }

    /**
     * 发布索引版本：原子切换线上版本。
     *
     * @param versionId 版本 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long versionId) {
        WbIndexVersion version = requireVersion(versionId);
        ComboSnapshot versionCombo = requireVersionCombo(version);
        ThrowUtil.throwIf(versionCombo.isListScope(), ErrorCode.INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN);
        WbIndexSet set = requireSetOf(version);
        Long currentId = set.getCurrentPublishedVersionId();
        if (Objects.equals(currentId, versionId)) {
            log.info("===> IndexSetServiceImpl 版本已在线，发布幂等跳过, versionId={}", versionId);
            return;
        }
        ThrowUtil.throwIf(!IndexVersionStatus.READY.name().equals(version.getStatus()),
                ErrorCode.INDEX_BUILDING_CONFLICT, "仅就绪版本可发布");

        LocalDateTime now = LocalDateTime.now();
        WbIndexVersion old = retireVersion(currentId, now);
        indexSetDbService.updatePublishedVersion(set.getId(), versionId);
        version.setStatus(IndexVersionStatus.ONLINE.name());
        version.setPublishedAt(now);
        // publishedBy 占位 null（接入认证后取登录用户）
        indexVersionDbService.updateById(version);

        WbKnowledgeBase kb = knowledgeBaseDbService.getById(set.getKnowledgeBaseId());
        if (NullUtil.isNotNull(kb) && NullUtil.isNull(kb.getPublishedIndexSetId())) {
            kb.setPublishedIndexSetId(set.getId());
            knowledgeBaseDbService.updateById(kb);
        }

        kbAuditLogDbService.saveAudit(AuditActionType.PUBLISH_INDEX, AUDIT_OBJECT_TYPE, versionId,
                NullUtil.isNull(old) ? null : old.getVersionNo(), version.getVersionNo());
        log.info("===> IndexSetServiceImpl 索引发布完成, kbId={}, versionId={}, versionNo={}, oldVersionNo={}",
                set.getKnowledgeBaseId(), versionId, version.getVersionNo(),
                NullUtil.isNull(old) ? null : old.getVersionNo());
    }

    /**
     * 回退到指定索引版本。
     *
     * @param versionId 版本 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long versionId) {
        WbIndexVersion target = indexVersionDbService.getById(versionId);
        ThrowUtil.throwIf(NullUtil.isNull(target), ErrorCode.INDEX_VERSION_NOT_FOUND);
        WbIndexSet set = indexSetDbService.getById(target.getIndexSetId());
        ThrowUtil.throwIf(NullUtil.isNull(set), ErrorCode.INDEX_VERSION_NOT_FOUND);
        ComboSnapshot targetCombo = requireVersionCombo(target);
        ThrowUtil.throwIf(targetCombo.isListScope(), ErrorCode.INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN);
        Long currentId = set.getCurrentPublishedVersionId();
        ThrowUtil.throwIf(NullUtil.isNull(currentId), ErrorCode.INDEX_NOT_PUBLISHED, "当前无在线版本，无法回退");
        ThrowUtil.throwIf(Objects.equals(currentId, versionId), ErrorCode.PARAM_INVALID, "目标版本即当前在线版本");
        ThrowUtil.throwIf(IndexVersionStatus.BUILDING.name().equals(target.getStatus()),
                ErrorCode.INDEX_BUILDING_CONFLICT, "目标版本构建中，无法回退");

        LocalDateTime now = LocalDateTime.now();
        WbIndexVersion current = retireVersion(currentId, now);
        indexSetDbService.updatePublishedVersion(set.getId(), versionId);
        // 目标复活：清退役留痕 + 重记发布时间
        target.setStatus(IndexVersionStatus.ONLINE.name());
        target.setPublishedAt(now);
        target.setRetiredAt(null);
        target.setRetiredBy(null);
        indexVersionDbService.updateById(target);

        kbAuditLogDbService.saveAudit(AuditActionType.ROLLBACK_INDEX, AUDIT_OBJECT_TYPE, versionId,
                NullUtil.isNull(current) ? null : current.getVersionNo(), target.getVersionNo());
        log.info("===> IndexSetServiceImpl 索引回退完成（指针切回）, kbId={}, targetVersionNo={}, oldVersionNo={}",
                set.getKnowledgeBaseId(), target.getVersionNo(),
                NullUtil.isNull(current) ? null : current.getVersionNo());
        compensate(set, target);
    }

    /**
     * 当前发布版本（versionNo → 集合名 kb_{kbId}_{versionNo} 直连检索）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 当前发布的版本；没有则为 null
     */
    public WbIndexVersion currentPublished(Long knowledgeBaseId) {
        WbIndexSet set = indexSetDbService.getByKb(knowledgeBaseId);
        if (NullUtil.isNull(set) || NullUtil.isNull(set.getCurrentPublishedVersionId())) {
            return null;
        }
        return indexVersionDbService.getById(set.getCurrentPublishedVersionId());
    }

    /**
     * 回收索引候选：删除版本与其向量集合。
     *
     * @param versionId 版本 ID
     */
    @Override
    public void recycle(Long versionId) {
        WbIndexVersion version = requireVersion(versionId);
        WbIndexSet set = requireSetOf(version);
        ThrowUtil.throwIf(Objects.equals(versionId, set.getCurrentPublishedVersionId()),
                ErrorCode.INDEX_ONLINE_DELETE_FORBIDDEN);
        ThrowUtil.throwIf(IndexVersionStatus.BUILDING.name().equals(version.getStatus()),
                ErrorCode.INDEX_BUILDING_CONFLICT);
        ThrowUtil.throwIf(IndexVersionStatus.RETIRED.name().equals(version.getStatus()),
                ErrorCode.PARAM_INVALID, "已退役版本保留用于回退，探索期不回收");

        // 先回收 Milvus 集合（外部 IO，事务外）：回收失败版本行保留可重试；
        // 回收成功后再用短事务删版本行并写审计，DB 失败时版本行仍在。
        milvusIndexPort.drop(MilvusIndexPort.collectionName(set.getKnowledgeBaseId(), version.getVersionNo()));
        transactionTemplate.executeWithoutResult(status -> {
            kbAuditLogDbService.saveAudit(AuditActionType.RECYCLE_INDEX, AUDIT_OBJECT_TYPE, versionId,
                    version.getVersionNo(), null);
            indexVersionDbService.removeById(versionId);
        });
        log.info("===> IndexSetServiceImpl 索引候选回收完成, kbId={}, versionId={}, versionNo={}",
                set.getKnowledgeBaseId(), versionId, version.getVersionNo());
    }

    /**
     * 列出知识库的索引版本。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 索引版本列表
     */
    @Override
    public List<IndexVersionVO> listVersions(Long knowledgeBaseId) {
        WbIndexSet set = indexSetDbService.getByKb(knowledgeBaseId);
        if (NullUtil.isNull(set)) {
            return List.of();
        }
        return indexVersionDbService.listByIndexSetId(set.getId()).stream()
                .map(v -> toVersionVO(v, Objects.equals(v.getId(), set.getCurrentPublishedVersionId())))
                .toList();
    }

    /**
     * 索引版本详情。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param versionId       版本 ID
     * @return 版本详情
     */
    @Override
    public IndexVersionVO versionDetail(Long knowledgeBaseId, Long versionId) {
        WbIndexSet set = indexSetDbService.getByKb(knowledgeBaseId);
        ThrowUtil.throwIf(NullUtil.isNull(set), ErrorCode.INDEX_VERSION_NOT_FOUND);
        WbIndexVersion version = indexVersionDbService.getById(versionId);
        ThrowUtil.throwIf(NullUtil.isNull(version) || !Objects.equals(set.getId(), version.getIndexSetId()),
                ErrorCode.INDEX_VERSION_NOT_FOUND);
        return toVersionVO(version, Objects.equals(versionId, set.getCurrentPublishedVersionId()));
    }

    /**
     * 列出可构建的索引组合。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param fileResultIds   限定参与的文件结果 ID（可空）
     * @return 组合列表
     */
    @Override
    public List<IndexComboVO> listCombos(Long knowledgeBaseId, List<Long> fileResultIds) {
        // 组合 × 成员口径（测评模式构建弹窗数据源）：候选三元组 = 范围内各文件各策略历史的并集；
        // 成员 = 该组合下有完整成功产物链的文件（selectComboProducts 单一取数口径，与构建对账同口径）。
        // 注意：与生产路径 enumerateCombos 的"全库交集"口径不同（本方法按成员子集返回，交集口径不动）。
        boolean scoped = NullUtil.isNotNull(fileResultIds) && !fileResultIds.isEmpty();
        List<Long> fileIds = scoped ? new ArrayList<>(fileResultIds)
                : fileResultDbService.listByKb(knowledgeBaseId).stream().map(WbFileResult::getId).toList();
        if (fileIds.isEmpty()) {
            return List.of();
        }
        IndexComboReconciler.ComboCatalog catalog = indexComboReconciler.catalog(fileIds);

        List<IndexComboVO> vos = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : catalog.preprocessByChunk().entrySet()) {
            for (String preprocess : entry.getValue()) {
                for (String embedStrategy : catalog.embedStrategies()) {
                    ComboSnapshot combo = ComboSnapshot.of(preprocess, entry.getKey(), embedStrategy);
                    List<Long> members = new ArrayList<>();
                    int vectorCount = 0;
                    for (Long fileId : fileIds) {
                        IndexComboReconciler.ComboProducts products =
                                indexComboReconciler.selectComboProducts(combo, fileId,
                                        catalog.latestChunk(), catalog.latestEmbed());
                        if (products.complete()) {
                            members.add(fileId);
                            vectorCount += ObjectUtil.defaultIfNull(products.embedRow().getRecordCount(), 0);
                        }
                    }
                    if (!members.isEmpty()) {
                        vos.add(toComboVO(combo, members, vectorCount));
                    }
                }
            }
        }
        return vos;
    }

    /** 组合 → 可构建组合视图（成员子集 + 文件数 × 向量数预览） */
    private IndexComboVO toComboVO(ComboSnapshot combo, List<Long> members, int vectorCount) {
        IndexComboVO vo = new IndexComboVO();
        vo.setFileScopeMode("ALL");
        vo.setChunkStrategy(combo.getChunkStrategy());
        vo.setEmbedStrategy(combo.getEmbedStrategy());
        vo.setShape(combo.getShape());
        vo.setStageStrategies(combo.getStageStrategies());
        vo.setComplete(true);
        vo.setFileResultIds(members);
        vo.setFileCount(members.size());
        vo.setVectorCount(vectorCount);
        return vo;
    }

    /**
     * 校验索引候选：对账组合范围与向量集合是否一致。
     *
     * @param versionId 版本 ID
     * @return 校验结果
     */
    @Override
    public IndexValidateVO validate(Long versionId) {
        WbIndexVersion version = requireVersion(versionId);
        WbIndexSet set = requireSetOf(version);
        ThrowUtil.throwIf(!IndexVersionStatus.READY.name().equals(version.getStatus())
                        && !IndexVersionStatus.ONLINE.name().equals(version.getStatus())
                        && !IndexVersionStatus.RETIRED.name().equals(version.getStatus()),
                ErrorCode.INDEX_BUILDING_CONFLICT, "仅就绪/在线/已退役版本可验证");
        Long kbId = set.getKnowledgeBaseId();
        ComboSnapshot combo = requireVersionCombo(version);

        // ① 对账期望（血缘感知 + 实时重算，与构建任务同一口径）+ 冒烟样本
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

    /** 版本行 → VO（组合快照结构化展开；无快照字段留空） */
    private IndexVersionVO toVersionVO(WbIndexVersion version, boolean online) {
        IndexVersionVO vo = new IndexVersionVO();
        vo.setId(version.getId());
        vo.setVersionNo(version.getVersionNo());
        if (StrUtil.isNotBlank(version.getComboSnapshot())) {
            ComboSnapshot combo = JsonUtil.toObject(version.getComboSnapshot(), ComboSnapshot.class);
            if (NullUtil.isNotNull(combo)) {
                vo.setFileScopeMode(combo.getFileScopeMode());
                vo.setFileResultIds(combo.getFileResultIds());
                vo.setChunkStrategy(combo.getChunkStrategy());
                vo.setEmbedStrategy(combo.getEmbedStrategy());
                vo.setShape(combo.getShape());
                vo.setStageStrategies(combo.getStageStrategies());
            }
        }
        vo.setChunkCount(version.getChunkCount());
        vo.setVectorCount(version.getVectorCount());
        vo.setStatus(version.getStatus());
        vo.setBuildError(version.getBuildError());
        vo.setTaskId(version.getTaskId());
        vo.setValidatedAt(version.getValidatedAt());
        vo.setPublishedAt(version.getPublishedAt());
        vo.setPublishedBy(version.getPublishedBy());
        vo.setRetiredAt(version.getRetiredAt());
        vo.setRetiredBy(version.getRetiredBy());
        vo.setCreateTime(version.getCreateTime());
        vo.setOnline(online);
        return vo;
    }

    /** 知识库策略绑定开关：关闭（0）→ true；开启/null 视为开启 → false */
    private boolean bindingDisabled(Long kbId) {
        WbKnowledgeBase kb = knowledgeBaseDbService.getById(kbId);
        return NullUtil.isNotNull(kb) && StrategyBindingSwitch.isOff(kb.getStrategyBindingEnabled());
    }

    /** 版本行失败回写（buildError 截断 1024） */
    private void markFailed(WbIndexVersion version, String buildError) {
        version.setStatus(IndexVersionStatus.FAILED.name());
        version.setBuildError(StrUtil.maxLength(buildError, 1024));
        indexVersionDbService.updateById(version);
    }

    /** 退役在线版本（已退役跳过）；返回该版本行（无 → null；retiredBy 占位 null） */
    private WbIndexVersion retireVersion(Long versionId, LocalDateTime now) {
        WbIndexVersion version = NullUtil.isNull(versionId) ? null : indexVersionDbService.getById(versionId);
        if (NullUtil.isNotNull(version) && !IndexVersionStatus.RETIRED.name().equals(version.getStatus())) {
            version.setStatus(IndexVersionStatus.RETIRED.name());
            version.setRetiredAt(now);
            indexVersionDbService.updateById(version);
        }
        return version;
    }

    /**
     * 构建前置校验 + 建版本行 + BUILD_INDEX 任务入队。
     * 防重口径（按触发类型）：显式=同组合活跃版本（CREATED/BUILDING/READY）冲突 40443；
     * INCREMENT/COMPENSATE=仅 CREATED/BUILDING 冲突（READY 允许重开，覆盖回退复活后的补齐闭环）；
     * NEW（绑定关候选）=同组合 CREATED/BUILDING/READY 均跳过（避免候选刷屏）。
     */
    private IndexBuildTriggerVO tryBuild(Long kbId, ComboSnapshot combo, String trigger, boolean explicit) {
        WbIndexSet set = indexSetDbService.getOrCreateByKb(kbId);
        String comboJson = JsonUtil.toJsonStr(combo);
        WbIndexVersion active = findActiveVersion(set.getId(), comboJson);
        if (NullUtil.isNotNull(active)) {
            boolean buildingLike = IndexVersionStatus.CREATED.name().equals(active.getStatus())
                    || IndexVersionStatus.BUILDING.name().equals(active.getStatus());
            ThrowUtil.throwIf(explicit, ErrorCode.INDEX_BUILDING_CONFLICT, "该组合已有活跃版本");
            boolean skip;
            if (IndexBuildTrigger.INCREMENT.name().equals(trigger)
                    || IndexBuildTrigger.COMPENSATE.name().equals(trigger)) {
                skip = buildingLike;
            } else {
                // NEW 候选：READY 也跳过
                skip = true;
            }
            if (skip) {
                log.info("===> IndexSetServiceImpl 组合已有活跃版本，跳过自动构建, kbId={}, versionId={}, trigger={}",
                        kbId, active.getId(), trigger);
                return null;
            }
        }
        return createVersionAndTask(kbId, combo, trigger);
    }

    /** 同组合且未终结（CREATED/BUILDING/READY）的版本行（无 → null） */
    private WbIndexVersion findActiveVersion(Long indexSetId, String comboJson) {
        return indexVersionDbService.listByIndexSetId(indexSetId).stream()
                .filter(v -> comboJson.equals(v.getComboSnapshot()))
                .filter(v -> Set.of(IndexVersionStatus.CREATED.name(),
                                IndexVersionStatus.BUILDING.name(),
                                IndexVersionStatus.READY.name(),
                                IndexVersionStatus.ONLINE.name())
                        .contains(v.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private IndexBuildTriggerVO createVersionAndTask(Long kbId, ComboSnapshot combo, String trigger) {
        WbIndexSet set = indexSetDbService.getOrCreateByKb(kbId);
        String versionNo = indexVersionDbService.nextVersionNo(set.getId());
        WbIndexVersion version = new WbIndexVersion();
        version.setIndexSetId(set.getId());
        version.setVersionNo(versionNo);
        version.setComboSnapshot(JsonUtil.toJsonStr(combo));
        version.setStatus(IndexVersionStatus.CREATED.name());
        try {
            indexVersionDbService.save(version);
        } catch (DuplicateKeyException e) {
            // uk_set_version 并发兜底：版本号冲突重取一次
            version.setVersionNo(indexVersionDbService.nextVersionNo(set.getId()));
            indexVersionDbService.save(version);
        }

        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(kbId);
        order.setComboSnapshot(combo);
        order.setTrigger(trigger);
        WbPipelineTask task = new WbPipelineTask();
        task.setStage(PipelineStage.BUILD_INDEX.name());
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        task.setStrategySnapshot(JsonUtil.toJsonStr(order));
        pipelineTaskDbService.save(task);

        version.setTaskId(task.getId());
        indexVersionDbService.updateById(version);
        taskQueue.enqueue(task.getId());
        log.info("===> IndexSetServiceImpl 索引构建任务入队, kbId={}, versionId={}, versionNo={}, taskId={}, trigger={}",
                kbId, version.getId(), versionNo, task.getId(), trigger);
        return new IndexBuildTriggerVO(version.getId(), versionNo, task.getId());
    }

    /**
     * 回退补齐（事务内建任务，随指针切回一起落库；Redis 入队由 TaskQueueSupport 延迟到提交后）：
     * 差异文件（缺目标组合最新成功切片/向量产物）→ 按目标切片策略重跑 CHUNK
     * （EMBED 仍手动逐环节，完成后 onFileProductsReady 自动构建+自动发布闭环）；
     * 无差异文件 → 直接投递 COMPENSATE 构建（自动发布）。
     */
    private void compensate(WbIndexSet set, WbIndexVersion target) {
        Long kbId = set.getKnowledgeBaseId();
        ComboSnapshot combo = requireVersionCombo(target);
        List<Long> scope = scopeFileIds(kbId, combo);
        List<WbChunkSet> chunkSets = chunkSetDbService.listByFileResultIds(scope);
        List<WbEmbeddingSet> embedSets = embeddingSetDbService.listByFileResultIds(scope);
        Map<String, Long> latestEmbed = latestEmbedIdMap(embedSets);

        // 差异文件 = 缺目标组合产物（血缘感知：切片策略匹配且上游预处理血缘匹配才算齐）+ 缺向量产物
        List<Long> diffFiles = new ArrayList<>();
        for (Long fileId : scope) {
            boolean chunkMatch = chunkSets.stream().anyMatch(s -> fileId.equals(s.getFileResultId())
                    && combo.getChunkStrategy().equals(s.getChunkStrategyVersion())
                    && combo.getPreprocessStrategy().equals(
                            lineageResolver.resolvePreprocessStrategy(s.getUpstreamProductId())));
            if (!chunkMatch || !latestEmbed.containsKey(fileId + "#" + combo.getEmbedStrategy())) {
                diffFiles.add(fileId);
            }
        }
        if (diffFiles.isEmpty()) {
            // 无差异：按目标组合补构建（COMPENSATE 恒自动发布，闭环）
            tryBuild(kbId, combo, IndexBuildTrigger.COMPENSATE.name(), false);
            return;
        }
        // 有差异：拓扑重放补齐（从缺失的最上游环节投递；投递幂等由各环节防重保证）
        for (Long fileId : diffFiles) {
            backfillFile(fileId, combo);
        }
        log.info("===> IndexSetServiceImpl 回退补齐投递完成, kbId={}, diffFiles={}", kbId, diffFiles.size());
    }

    private List<Long> scopeFileIds(Long kbId, ComboSnapshot combo) {
        if ("LIST".equals(combo.getFileScopeMode()) && NullUtil.isNotNull(combo.getFileResultIds())) {
            return combo.getFileResultIds();
        }
        return fileResultDbService.listByKb(kbId).stream().map(WbFileResult::getId).toList();
    }

    /** 文件级「同策略最新成功」向量：append-only 表取 id 最大（与 IndexComboServiceImpl 同口径） */
    private Map<String, Long> latestEmbedIdMap(List<WbEmbeddingSet> embedSets) {
        Map<String, Long> latest = new LinkedHashMap<>();
        for (WbEmbeddingSet set : embedSets) {
            String key = set.getFileResultId() + "#" + set.getStrategyVersion();
            latest.merge(key, set.getId(), Math::max);
        }
        return latest;
    }
}
