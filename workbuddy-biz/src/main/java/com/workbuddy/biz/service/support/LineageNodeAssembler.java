package com.workbuddy.biz.service.support;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.dto.response.lineage.LineageParseStatsVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行树节点组装：任务 + 产物 + 批量产物 → 节点，并回填环节统计、摘要与策略类口径。
 *
 * <p>节点=一次运行；通用统计按 artifactId 匹配集合表，PARSE/STRUCTURE/PREPROCESS/CHUNK
 * 另读产物本体现算；step_log 聚合作为预处理环节的 matched/changed 口径。
 */
@Slf4j
public final class LineageNodeAssembler {

    /** 有策略环节（strategySnapshot 为策略快照，节点展示 strategyVersion） */
    private static final Set<String> STRATEGY_STAGES = Set.of(
            PipelineStage.PREPROCESS.name(), PipelineStage.CHUNK.name(), PipelineStage.EMBED.name());

    private LineageNodeAssembler() {
    }

    /**
     * 预处理统计聚合（step_log）：[0]=matchedCount 合计、[1]=changedCount 合计。
     *
     * @param tasks            本次血缘的任务列表
     * @param stepLogDbService 子步骤日志数据访问
     * @return 任务 ID → [matched, changed]；无预处理任务时为空 map
     */
    public static Map<Long, long[]> aggregateStepLogs(List<WbPipelineTask> tasks,
                                                      WbPipelineStepLogDbService stepLogDbService) {
        List<Long> preprocessTaskIds = tasks.stream()
                .filter(task -> PipelineStage.PREPROCESS.name().equals(task.getStage()))
                .map(WbPipelineTask::getId)
                .toList();
        if (preprocessTaskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, long[]> agg = new HashMap<>();
        for (WbPipelineStepLog stepLog : stepLogDbService.listByTaskIds(preprocessTaskIds)) {
            long[] sum = agg.computeIfAbsent(stepLog.getTaskId(), k -> new long[] { 0, 0 });
            sum[0] += ObjectUtil.defaultIfNull(stepLog.getMatchedCount(), 0);
            sum[1] += ObjectUtil.defaultIfNull(stepLog.getChangedCount(), 0);
        }
        return agg;
    }

    /**
     * 任务 + 产物 → 血缘节点：基础字段、策略版本、能力快照与环节统计逐项回填。
     *
     * @param task                任务行
     * @param product             该次运行的产物行，可空
     * @param stepAgg             预处理 step_log 聚合
     * @param artifacts           批量产物（本体按任务 ID、集合行按产物键索引）
     * @param chunkStrategyParser 切片策略解析器
     * @return 血缘节点
     */
    public static LineageNodeVO toNode(WbPipelineTask task, WbPipelineProduct product,
                                       Map<Long, long[]> stepAgg,
                                       LineageArtifactReader.NodeArtifacts artifacts,
                                       ChunkStrategyParser chunkStrategyParser) {
        LineageNodeVO node = new LineageNodeVO();
        node.setTaskId(task.getId());
        node.setProductId(task.getProductId());
        node.setStage(task.getStage());
        node.setStatus(task.getStatus());
        node.setErrorCode(task.getErrorCode());
        node.setErrorMsg(task.getErrorMsg());
        node.setStartedAt(task.getStartedAt());
        node.setFinishedAt(task.getFinishedAt());
        if (STRATEGY_STAGES.contains(task.getStage())) {
            node.setStrategyVersion(LineageCapabilitySupport.strategyVersion(task.getStrategySnapshot()));
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        boolean structureStage = PipelineStage.STRUCTURE.name().equals(task.getStage());
        boolean preprocessStage = PipelineStage.PREPROCESS.name().equals(task.getStage());
        boolean chunkStage = PipelineStage.CHUNK.name().equals(task.getStage());
        if (NullUtil.isNotNull(product)) {
            node.setArtifactId(product.getArtifactId());
            node.setContentHash(product.getContentHash());
            if (PipelineStage.PARSE.name().equals(task.getStage())
                    || PipelineStage.STRUCTURE.name().equals(task.getStage())) {
                node.setCapability(LineageCapabilitySupport.capability(product.getCapabilitySnapshot()));
            }
            if (PipelineStage.PARSE.name().equals(task.getStage())) {
                fillParseStats(node, task, artifacts.parseResults().get(task.getId()));
            }
            fillStats(stats, task, product, artifacts, stepAgg);
        }
        // 组装统计在通用统计之后落位：通用 map 只覆盖切片与向量化等环节，而组装统计由本环节独占，
        // 放在它之前会被随后的空 map 覆盖掉。
        if (structureStage) {
            fillStructureStats(stats, node, task, artifacts.documents().get(task.getId()));
        }
        // 预处理统计在通用统计之后并入：通用 map 里的 matched/changed 由 step_log 聚合而来，必须保留
        if (preprocessStage) {
            fillPreprocessStats(stats, node, task, artifacts.views().get(task.getId()));
        }
        // 切片统计同样在通用统计之后并入：通用 map 里的 chunkCount 由集合表聚合而来，必须保留
        if (chunkStage) {
            fillChunkStats(stats, node, task, artifacts, chunkStrategyParser);
        }
        node.setStats(StageStatsAssembler.from(task.getStage(), stats));
        return node;
    }

    /** 解析节点统计与摘要行一并回填：指标行与构成图取统计字段，摘要行按统计与状态陈述 */
    private static void fillParseStats(LineageNodeVO node, WbPipelineTask task, ParseResult parseResult) {
        LineageParseStatsVO stats = ParseStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(), parseResult);
        node.setParseStats(stats);
        node.setParseSummary(ParseStatsSupport.summary(node.getErrorMsg(), stats, task.getStatus()));
    }

    /** 组装节点统计与摘要行一并回填：本环节统计整份替换本地 map，摘要走通用 stageSummary */
    private static void fillStructureStats(Map<String, Object> stats, LineageNodeVO node, WbPipelineTask task,
                                           UnifiedDocument document) {
        Map<String, Object> structureStats =
                StructureStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(), document);
        node.setStageSummary(
                StructureStatsSupport.summary(node.getErrorMsg(), structureStats, task.getStatus()));
        if (NullUtil.isNotNull(structureStats)) {
            stats.clear();
            stats.putAll(structureStats);
        }
    }

    /**
     * 预处理节点统计与摘要行一并回填：统计并入本地 map（保留 step_log 聚合来的 matched/changed），
     * 摘要走通用 stageSummary。
     */
    private static void fillPreprocessStats(Map<String, Object> stats, LineageNodeVO node, WbPipelineTask task,
                                            PreprocessView view) {
        Map<String, Object> preprocessStats =
                PreprocessStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(), view);
        node.setStageSummary(
                PreprocessStatsSupport.summary(node.getErrorMsg(), preprocessStats, task.getStatus()));
        if (NullUtil.isNotNull(preprocessStats)) {
            stats.putAll(preprocessStats);
        }
    }

    /**
     * 切片节点统计与摘要行一并回填：统计并入本地 map（保留集合表聚合来的 chunkCount），
     * 摘要走通用 stageSummary；片长上限与兜底参数取该次运行的策略快照。
     *
     * <p>快照缺失或损坏时策略取 null（不构造默认策略）：策略类口径整组留空，其余统计照常下发。
     */
    private static void fillChunkStats(Map<String, Object> stats, LineageNodeVO node, WbPipelineTask task,
                                       LineageArtifactReader.NodeArtifacts artifacts,
                                       ChunkStrategyParser chunkStrategyParser) {
        Map<String, Object> chunkStats = ChunkStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(),
                artifacts.chunkSets().get(task.getId()), chunkStrategyOf(task, chunkStrategyParser),
                artifacts.upstreamViews().get(task.getId()));
        node.setStageSummary(ChunkStatsSupport.summary(node.getErrorMsg(), chunkStats, task.getStatus()));
        if (NullUtil.isNotNull(chunkStats)) {
            stats.putAll(chunkStats);
        }
    }

    /**
     * 任务策略快照 → 切片策略；缺失或损坏返回 null（不构造默认策略），策略类口径随之为空。
     *
     * @param task                切片任务行
     * @param chunkStrategyParser 切片策略解析器
     * @return 补全后的切片策略；缺失或损坏返回 null
     */
    private static ChunkStrategy chunkStrategyOf(WbPipelineTask task, ChunkStrategyParser chunkStrategyParser) {
        try {
            return chunkStrategyParser.parse(task.getStrategySnapshot());
        } catch (Exception e) {
            log.warn("执行树切片节点策略快照缺失或损坏，策略类口径留空, taskId={}", task.getId(), e);
            return null;
        }
    }

    /** 统计摘要：CHUNK/EMBED 按 artifactId 匹配集合表；PREPROCESS 用 step_log 聚合 */
    private static void fillStats(Map<String, Object> stats, WbPipelineTask task, WbPipelineProduct product,
                                  LineageArtifactReader.NodeArtifacts artifacts, Map<Long, long[]> stepAgg) {
        if (PipelineStage.CHUNK.name().equals(task.getStage())) {
            WbChunkSet chunkSet = artifacts.chunkSetByArtifact().get(product.getArtifactId());
            if (NullUtil.isNotNull(chunkSet)) {
                stats.put("chunkCount", chunkSet.getChunkCount());
            }
        } else if (PipelineStage.EMBED.name().equals(task.getStage())) {
            WbEmbeddingSet embedSet = artifacts.embedSetByArtifact().get(product.getArtifactId());
            if (NullUtil.isNotNull(embedSet)) {
                stats.put("recordCount", embedSet.getRecordCount());
                stats.put("cachedCount", embedSet.getCachedCount());
            }
        } else if (PipelineStage.PREPROCESS.name().equals(task.getStage())) {
            long[] agg = stepAgg.getOrDefault(task.getId(), new long[] { 0, 0 });
            stats.put("matched", agg[0]);
            stats.put("changed", agg[1]);
        }
    }
}
