package com.workbuddy.biz.service.support.lineage;

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
import com.workbuddy.common.dto.response.chunk.ChunkStatsVO;
import com.workbuddy.common.dto.response.embed.EmbedStatsVO;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.dto.response.lineage.LineageParseStatsVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessStatsVO;
import com.workbuddy.common.dto.response.structure.StructureStatsVO;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.biz.service.support.stats.ParseStatsSupport;
import com.workbuddy.biz.service.support.stats.StructureStatsSupport;
import com.workbuddy.biz.service.support.stats.PreprocessStatsSupport;
import com.workbuddy.biz.service.support.stats.ChunkStatsSupport;
import com.workbuddy.biz.service.support.artifact.LineageArtifactReader;
import com.workbuddy.biz.service.support.common.LineageCapabilitySupport;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
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
public final class LineageNodeSupport {

    /** 有策略环节（strategySnapshot 为策略快照，节点展示 strategyVersion） */
    private static final Set<String> STRATEGY_STAGES = Set.of(
            PipelineStage.PREPROCESS.name(), PipelineStage.CHUNK.name(), PipelineStage.EMBED.name());

    private LineageNodeSupport() {
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
        StageStatsVO stats;
        boolean structureStage = PipelineStage.STRUCTURE.name().equals(task.getStage());
        boolean preprocessStage = PipelineStage.PREPROCESS.name().equals(task.getStage());
        boolean chunkStage = PipelineStage.CHUNK.name().equals(task.getStage());
        boolean embedStage = PipelineStage.EMBED.name().equals(task.getStage());
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
        }
        // 环节统计由各环节的统计支持类直接产出具体 VO；库表聚合口径随环节并入同一个 VO
        if (structureStage) {
            stats = fillStructureStats(node, task, artifacts.documents().get(task.getId()));
        } else if (preprocessStage) {
            stats = fillPreprocessStats(node, task, artifacts.views().get(task.getId()), product,
                    stepAgg.get(task.getId()));
        } else if (chunkStage) {
            stats = fillChunkStats(node, task, artifacts, chunkStrategyParser, product);
        } else if (embedStage) {
            stats = fillEmbedStats(product, artifacts);
        } else {
            stats = new StageStatsVO();
        }
        node.setStats(stats);
        return node;
    }

    /** 解析节点统计与摘要行一并回填：指标行与构成图取统计字段，摘要行按统计与状态陈述 */
    private static void fillParseStats(LineageNodeVO node, WbPipelineTask task, ParseResult parseResult) {
        LineageParseStatsVO stats = ParseStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(), parseResult);
        node.setParseStats(stats);
        node.setParseSummary(ParseStatsSupport.summary(node.getErrorMsg(), stats, task.getStatus()));
    }

    /** 组装节点统计与摘要行一并回填：统计由组装统计支持类产出，摘要走通用 stageSummary */
    private static StructureStatsVO fillStructureStats(LineageNodeVO node, WbPipelineTask task,
                                                       UnifiedDocument document) {
        StructureStatsVO stats =
                StructureStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(), document);
        node.setStageSummary(StructureStatsSupport.summary(node.getErrorMsg(), stats, task.getStatus()));
        return NullUtil.isNull(stats) ? new StructureStatsVO() : stats;
    }

    /**
     * 预处理节点统计与摘要行一并回填：产物统计由预处理支持类产出，matched/changed（step_log 聚合）
     * 并入同一个 VO；摘要走通用 stageSummary。
     */
    private static PreprocessStatsVO fillPreprocessStats(LineageNodeVO node, WbPipelineTask task,
                                                         PreprocessView view, WbPipelineProduct product,
                                                         long[] stepAgg) {
        PreprocessStatsVO stats =
                PreprocessStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(), view);
        node.setStageSummary(PreprocessStatsSupport.summary(node.getErrorMsg(), stats, task.getStatus()));
        PreprocessStatsVO result = NullUtil.isNull(stats) ? new PreprocessStatsVO() : stats;
        // 库表聚合口径只对有产物的节点陈述（无产物时不编造 0 值）
        if (NullUtil.isNotNull(product)) {
            long[] agg = ObjectUtil.defaultIfNull(stepAgg, new long[] { 0, 0 });
            result.setMatched(agg[0]);
            result.setChanged(agg[1]);
        }
        return result;
    }

    /**
     * 切片节点统计与摘要行一并回填：产物统计由切片支持类产出，集合表聚合的 chunkCount 在产物不可读时兜底；
     * 摘要走通用 stageSummary；片长上限与兜底参数取该次运行的策略快照。
     *
     * <p>快照缺失或损坏时策略取 null（不构造默认策略）：策略类口径整组留空，其余统计照常下发。
     */
    private static ChunkStatsVO fillChunkStats(LineageNodeVO node, WbPipelineTask task,
                                               LineageArtifactReader.NodeArtifacts artifacts,
                                               ChunkStrategyParser chunkStrategyParser,
                                               WbPipelineProduct product) {
        ChunkStatsVO stats = ChunkStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(),
                artifacts.chunkSets().get(task.getId()), chunkStrategyOf(task, chunkStrategyParser),
                artifacts.upstreamViews().get(task.getId()));
        node.setStageSummary(ChunkStatsSupport.summary(node.getErrorMsg(), stats, task.getStatus()));
        if (NullUtil.isNotNull(stats)) {
            return stats;
        }
        ChunkStatsVO result = new ChunkStatsVO();
        // 产物不可读时仍有集合表聚合的片数口径
        if (NullUtil.isNotNull(product) && NullUtil.isNotNull(artifacts.chunkSetByArtifact())) {
            WbChunkSet chunkSet = artifacts.chunkSetByArtifact().get(product.getArtifactId());
            if (NullUtil.isNotNull(chunkSet)) {
                result.setChunkCount(chunkSet.getChunkCount());
            }
        }
        return result;
    }

    /** 向量化节点统计：记录数与缓存命中数取集合表聚合（本环节无产物级统计） */
    private static EmbedStatsVO fillEmbedStats(WbPipelineProduct product,
                                               LineageArtifactReader.NodeArtifacts artifacts) {
        EmbedStatsVO stats = new EmbedStatsVO();
        if (NullUtil.isNull(product) || NullUtil.isNull(artifacts.embedSetByArtifact())) {
            return stats;
        }
        WbEmbeddingSet embedSet = artifacts.embedSetByArtifact().get(product.getArtifactId());
        if (NullUtil.isNotNull(embedSet)) {
            stats.setRecordCount(embedSet.getRecordCount());
            stats.setCachedCount(embedSet.getCachedCount());
        }
        return stats;
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

}
