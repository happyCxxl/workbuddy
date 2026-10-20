package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.IndexBuildService;
import com.workbuddy.biz.service.IndexComboService;
import com.workbuddy.biz.service.IndexPublishService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.index.IndexBuildSupport;
import com.workbuddy.biz.service.support.index.IndexLineageResolver;
import com.workbuddy.biz.service.support.assemble.IndexRowAssembler;
import com.workbuddy.biz.service.support.index.IndexVersionStateSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.common.enums.index.IndexBuildTrigger;
import com.workbuddy.common.enums.index.IndexVersionStatus;
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
import com.workbuddy.worker.indexing.MilvusIndexPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 索引构建实现：产物就绪追加与自动发布判定、候选注册构建、候选校验与回退补齐。
 * 版本状态与发布指针走 {@link IndexVersionStateSupport}，发布动作走 {@link IndexPublishService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexBuildServiceImpl implements IndexBuildService {

    private final WbKnowledgeBaseDbService knowledgeBaseDbService;
    private final WbIndexSetDbService indexSetDbService;
    private final WbIndexVersionDbService indexVersionDbService;
    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final WbFileResultDbService fileResultDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbEmbeddingSetDbService embeddingSetDbService;
    private final IndexComboService indexComboService;
    private final IndexLineageResolver lineageResolver;
    private final IndexRowAssembler indexRowAssembler;
    private final MilvusIndexPort milvusIndexPort;
    private final TaskQueueSupport taskQueue;
    private final IndexPublishService publishService;
    private final IndexVersionStateSupport stateSupport;
    private final IndexBuildSupport buildSupport;

    /**
     * 文件产物就绪后的自动注册钩子：绑定开启时按最新产物登记组合。
     *
     * @param fileResultId 文件结果 ID
     */
    @Override
    public void onFileProductsReady(Long fileResultId) {
        WbFileResult file = fileResultDbService.getById(fileResultId);
        if (NullUtil.isNull(file)) {
            log.warn("===> IndexBuildServiceImpl 文件产物就绪回调但文件结果不存在, fileResultId={}", fileResultId);
            return;
        }
        Long kbId = file.getKnowledgeBaseId();

        // ① 血缘解析：文件最新产物的组合（追加目标按产物实际组合决定，不按当前在线/绑定）
        ProductCombo product = resolveProductCombo(fileResultId);
        if (NullUtil.isNull(product)) {
            log.info("===> IndexBuildServiceImpl 产物血统不完整，跳过追加, fileResultId={}", fileResultId);
            return;
        }

        // ② 定位/复用组合集合（绑定关=评测模式不自动注册新组合；无活跃版本时跳过追加）
        WbIndexVersion version = registerComboCollection(kbId, product.combo(), product.dimension());
        List<IndexRow> rows = indexRowAssembler.assemble(fileResultId,
                StrUtil.blankToDefault(file.getOwner(), ""), product.chunkRow(), product.embedRow());
        if (NullUtil.isNotNull(version)) {
            if (NullUtil.isNull(rows)) {
                stateSupport.markFailed(version, "文件 " + fileResultId + " 向量产物读取失败");
                return;
            }
            if (rows.isEmpty()) {
                log.info("===> IndexBuildServiceImpl 无可写记录（SUCCESS/CACHED 子集为空），跳过追加, fileResultId={}",
                        fileResultId);
                return;
            }
            String collectionName = MilvusIndexPort.collectionName(kbId, version.getVersionNo());
            milvusIndexPort.append(collectionName, rows);
            stateSupport.updateLedger(version, rows);
            buildSupport.reconcileFile(collectionName, fileResultId, rows);
            maybeAutoPublish(kbId, version, product.combo());
        }

        // ③ 规则三：产物组合 ≠ 在线组合 → 按在线组合全链补齐该文件（文件永不丢）
        maybeBackfillToOnline(kbId, fileResultId, product.combo());

        // ④ 评测冻结集路由：范围内文件的活跃 LIST 版本同步追加（冻结语义：只认 fileResultIds）
        if (NullUtil.isNotNull(rows) && !rows.isEmpty()) {
            buildSupport.appendToFrozenScopes(kbId, fileResultId, product.combo(), rows);
        }
    }

    /** 文件产物组合（血缘 + 维度 + 产物行） */
    private record ProductCombo(ComboSnapshot combo, int dimension, WbChunkSet chunkRow, WbEmbeddingSet embedRow) {
    }

    /** 组合的活跃版本查询结果：集合行 + 组合 JSON + 当前活跃版本。 */
    private record ActiveCombo(WbIndexSet set, String comboJson, WbIndexVersion active) {
    }

    /** 查询组合的活跃版本（集合行不存在时创建）。 */
    private ActiveCombo activeCombo(Long kbId, ComboSnapshot combo) {
        WbIndexSet set = indexSetDbService.getOrCreateByKb(kbId);
        String comboJson = JsonUtil.toJsonStr(combo);
        return new ActiveCombo(set, comboJson, stateSupport.findActiveVersion(set.getId(), comboJson));
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
        ActiveCombo resolved = activeCombo(kbId, combo);
        WbIndexSet set = resolved.set();
        String comboJson = resolved.comboJson();
        if (NullUtil.isNotNull(resolved.active())) {
            return resolved.active();
        }
        if (bindingDisabled(kbId)) {
            log.info("===> IndexBuildServiceImpl 评测模式（策略绑定关）跳过组合自动注册, kbId={}, combo={}",
                    kbId, comboJson);
            return null;
        }
        WbIndexVersion version = stateSupport.createVersion(set, comboJson, 0, 0);
        String collectionName = MilvusIndexPort.collectionName(kbId, version.getVersionNo());
        try {
            milvusIndexPort.ensureCollection(collectionName, dimension);
            milvusIndexPort.load(collectionName);
        } catch (Exception e) {
            stateSupport.markFailed(version, "集合初始化失败: " + StrUtil.maxLength(String.valueOf(e.getMessage()), 1000));
            return null;
        }
        log.info("===> IndexBuildServiceImpl 组合已注册, kbId={}, versionNo={}, combo={}",
                kbId, version.getVersionNo(), comboJson);
        return version;
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
        if (NullUtil.isNull(bound) || !stateSupport.comboEquals(bound, combo)) {
            return;
        }
        try {
            publishService.publish(version.getId());
        } catch (Exception e) {
            log.warn("===> IndexBuildServiceImpl 自动发布失败，停留 READY 等管理员, versionId={}", version.getId(), e);
        }
    }

    /** 规则三：产物组合 ≠ 在线组合 → 按在线组合全链补齐该文件（从缺失的最上游环节投递） */
    private void maybeBackfillToOnline(Long kbId, Long fileResultId, ComboSnapshot productCombo) {
        WbIndexVersion online = publishService.currentPublished(kbId);
        if (NullUtil.isNull(online)) {
            return;
        }
        ComboSnapshot onlineCombo = stateSupport.requireCombo(online);
        if (stateSupport.comboEquals(onlineCombo, productCombo)) {
            return;
        }
        buildSupport.backfillFile(fileResultId, onlineCombo);
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
     * 校验索引候选：对账组合范围与向量集合是否一致。
     *
     * @param versionId 版本 ID
     * @return 校验结果
     */
    @Override
    public IndexValidateVO validate(Long versionId) {
        return buildSupport.validate(versionId);
    }

    /**
     * 构建前置校验 + 建版本行 + BUILD_INDEX 任务入队。
     * 防重口径（按触发类型）：显式=同组合活跃版本（CREATED/BUILDING/READY）冲突 40443；
     * INCREMENT/COMPENSATE=仅 CREATED/BUILDING 冲突（READY 允许重开，覆盖回退复活后的补齐闭环）；
     * NEW（绑定关候选）=同组合 CREATED/BUILDING/READY 均跳过（避免候选刷屏）。
     */
    private IndexBuildTriggerVO tryBuild(Long kbId, ComboSnapshot combo, String trigger, boolean explicit) {
        ActiveCombo resolved = activeCombo(kbId, combo);
        WbIndexVersion active = resolved.active();
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
                log.info("===> IndexBuildServiceImpl 组合已有活跃版本，跳过自动构建, kbId={}, versionId={}, trigger={}",
                        kbId, active.getId(), trigger);
                return null;
            }
        }
        return createVersionAndTask(kbId, combo, trigger);
    }

    private IndexBuildTriggerVO createVersionAndTask(Long kbId, ComboSnapshot combo, String trigger) {
        WbIndexSet set = indexSetDbService.getOrCreateByKb(kbId);
        WbIndexVersion version = stateSupport.createVersion(set, JsonUtil.toJsonStr(combo), null, null);
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
        log.info("===> IndexBuildServiceImpl 索引构建任务入队, kbId={}, versionId={}, versionNo={}, taskId={}, "
                + "trigger={}", kbId, version.getId(), version.getVersionNo(), task.getId(), trigger);
        return new IndexBuildTriggerVO(version.getId(), version.getVersionNo(), task.getId());
    }

    /**
     * 回退补齐（事务内建任务，随指针切回一起落库；Redis 入队由 TaskQueueSupport 延迟到提交后）：
     * 差异文件（缺目标组合最新成功切片/向量产物）→ 按目标切片策略重跑 CHUNK
     * （EMBED 仍手动逐环节，完成后 onFileProductsReady 自动构建+自动发布闭环）；
     * 无差异文件 → 直接投递 COMPENSATE 构建（自动发布）。
     *
     * @param set    索引集合
     * @param target 回退目标版本
     */
    @Override
    public void compensate(WbIndexSet set, WbIndexVersion target) {
        Long kbId = set.getKnowledgeBaseId();
        ComboSnapshot combo = stateSupport.requireCombo(target);
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
            buildSupport.backfillFile(fileId, combo);
        }
        log.info("===> IndexBuildServiceImpl 回退补齐投递完成, kbId={}, diffFiles={}", kbId, diffFiles.size());
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

    /** 知识库策略绑定开关：关闭（0）→ true；开启/null 视为开启 → false */
    private boolean bindingDisabled(Long kbId) {
        WbKnowledgeBase kb = knowledgeBaseDbService.getById(kbId);
        return NullUtil.isNotNull(kb) && StrategyBindingSwitch.isOff(kb.getStrategyBindingEnabled());
    }
}
