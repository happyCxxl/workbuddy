package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.LineageQueryService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.ChunkStatsSupport;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.ParseStatsSupport;
import com.workbuddy.biz.service.support.PreprocessStatsSupport;
import com.workbuddy.biz.service.support.StatsSupport;
import com.workbuddy.biz.service.support.StructureStatsSupport;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.parse.CapabilitySnapshot;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.dto.response.lineage.LineageCapabilityVO;
import com.workbuddy.common.dto.response.lineage.LineageEdgeVO;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.dto.response.lineage.LineageParseStatsVO;
import com.workbuddy.common.dto.response.lineage.LineageVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行树聚合实现：批量 in 查询拼图（≈5 次 DB 访问），无 N+1。
 * 节点=一次运行；边=upstreamProductId 血缘反查（task.productId → product.id → 产出任务）；
 * 统计摘要按 artifactId 匹配（CHUNK=chunkCount、EMBED=recordCount/cachedCount、PREPROCESS=step_log 聚合），
 * PARSE 另读产物本体汇总元素构成、问题单元与耗时（每个解析任务一次对象读取）。
 *
 * @author cxxl
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageQueryServiceImpl implements LineageQueryService {

    /** 有策略环节（strategySnapshot 为策略快照，节点展示 strategyVersion） */
    private static final Set<String> STRATEGY_STAGES = Set.of(
            PipelineStage.PREPROCESS.name(), PipelineStage.CHUNK.name(), PipelineStage.EMBED.name());

    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final WbPipelineProductDbService pipelineProductDbService;
    private final WbPipelineStepLogDbService stepLogDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbEmbeddingSetDbService embeddingSetDbService;
    private final FileStorage fileStorage;
    private final FileResultAccessGuard accessGuard;
    private final ChunkStrategyParser chunkStrategyParser;

    /**
     * 按任务索引的产物本体：一次读取，多环节共用（统计口径都从这几份产物现算）。
     *
     * @param parseResults  解析产物（PARSE 节点统计）
     * @param documents     统一文档（STRUCTURE 节点统计）
     * @param views         预处理派生视图（PREPROCESS 节点统计）
     * @param chunkSets     切片集合（CHUNK 节点统计）
     * @param upstreamViews 切片任务 → 上游预处理视图（"进入切片 / 跳过"元素数的口径来源）
     */
    private record NodeArtifacts(Map<Long, ParseResult> parseResults, Map<Long, UnifiedDocument> documents,
                                 Map<Long, PreprocessView> views, Map<Long, ChunkSet> chunkSets,
                                 Map<Long, PreprocessView> upstreamViews) {
    }

    @Override
    public LineageVO lineage(Long fileResultId) {
        accessGuard.requireExisting(fileResultId);

        List<WbPipelineTask> tasks = pipelineTaskDbService.listByFileResultId(fileResultId).stream()
                .filter(task -> PipelineStage.FILE_CHAIN_STAGES.contains(task.getStage()))
                .toList();
        List<WbPipelineProduct> products = pipelineProductDbService.listByFileResultId(fileResultId);

        Map<Long, WbPipelineProduct> productById = products.stream()
                .collect(Collectors.toMap(WbPipelineProduct::getId, p -> p, (a, b) -> a));
        // 产物 ID → 产出任务 ID（血缘反查；task.productId 唯一）
        Map<Long, Long> taskIdByProductId = new HashMap<>();
        for (WbPipelineTask task : tasks) {
            if (NullUtil.isNotNull(task.getProductId())) {
                taskIdByProductId.put(task.getProductId(), task.getId());
            }
        }

        Map<String, WbChunkSet> chunkSetByArtifact = chunkSetDbService.listByFileResultId(fileResultId).stream()
                .collect(Collectors.toMap(WbChunkSet::getArtifactId, s -> s, (a, b) -> a));
        Map<String, WbEmbeddingSet> embedSetByArtifact = embeddingSetDbService.listByFileResultId(fileResultId)
                .stream()
                .collect(Collectors.toMap(WbEmbeddingSet::getArtifactId, s -> s, (a, b) -> a));
        Map<Long, long[]> stepAgg = aggregateStepLogs(tasks);
        Map<Long, ParseResult> parseResultByTaskId = readProductBodies(tasks, productById, PipelineStage.PARSE,
                PipelineStage.PARSE.getLabel(),
                product -> ParseStatsSupport.readArtifact(fileStorage, ObjectRef.ofProduct(product)));
        Map<Long, UnifiedDocument> documentByTaskId = readProductBodies(tasks, productById, PipelineStage.STRUCTURE,
                PipelineStage.STRUCTURE.getLabel(),
                product -> StructureStatsSupport.readDocument(fileStorage, ObjectRef.ofProduct(product)));
        Map<Long, PreprocessView> viewByTaskId = readProductBodies(tasks, productById, PipelineStage.PREPROCESS,
                PipelineStage.PREPROCESS.getLabel(),
                product -> PreprocessStatsSupport.readView(fileStorage, ObjectRef.ofProduct(product)));
        Map<Long, ChunkSet> chunkSetByTaskId = readProductBodies(tasks, productById, PipelineStage.CHUNK,
                PipelineStage.CHUNK.getLabel(),
                product -> ChunkStatsSupport.readChunkSet(fileStorage, ObjectRef.ofProduct(product)));
        // 切片节点要上游视图的"进入切片 / 跳过"元素数：上游产物已在本批读过，不再重复读对象
        Map<Long, PreprocessView> upstreamViewByTaskId =
                upstreamViewsOf(tasks, taskIdByProductId, viewByTaskId);
        NodeArtifacts artifacts = new NodeArtifacts(parseResultByTaskId, documentByTaskId, viewByTaskId,
                chunkSetByTaskId, upstreamViewByTaskId);

        // 节点：环节顺序（PARSE→…→EMBED）再按任务 id 升序
        List<WbPipelineTask> ordered = tasks.stream()
                .sorted((a, b) -> {
                    int stageCompare = Integer.compare(stageOrder(a.getStage()), stageOrder(b.getStage()));
                    return stageCompare != 0 ? stageCompare : Long.compare(a.getId(), b.getId());
                })
                .toList();

        List<LineageNodeVO> nodes = new ArrayList<>();
        List<LineageEdgeVO> edges = new ArrayList<>();
        for (WbPipelineTask task : ordered) {
            WbPipelineProduct product = NullUtil.isNull(task.getProductId()) ? null : productById.get(task.getProductId());
            nodes.add(toNode(task, product, chunkSetByArtifact, embedSetByArtifact, stepAgg, artifacts));
            if (NullUtil.isNotNull(task.getUpstreamProductId())) {
                Long fromTaskId = taskIdByProductId.get(task.getUpstreamProductId());
                if (NullUtil.isNotNull(fromTaskId)) {
                    LineageEdgeVO edge = new LineageEdgeVO();
                    edge.setFromTaskId(fromTaskId);
                    edge.setToTaskId(task.getId());
                    edges.add(edge);
                }
            }
        }

        LineageVO vo = new LineageVO();
        vo.setFileResultId(fileResultId);
        vo.setNodes(nodes);
        vo.setEdges(edges);
        return vo;
    }

    private LineageNodeVO toNode(WbPipelineTask task, WbPipelineProduct product,
                                 Map<String, WbChunkSet> chunkSetByArtifact,
                                 Map<String, WbEmbeddingSet> embedSetByArtifact,
                                 Map<Long, long[]> stepAgg,
                                 NodeArtifacts artifacts) {
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
            node.setStrategyVersion(resolveStrategyVersion(task.getStrategySnapshot()));
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
                node.setCapability(resolveCapability(product.getCapabilitySnapshot()));
            }
            if (PipelineStage.PARSE.name().equals(task.getStage())) {
                fillParseStats(node, task, artifacts.parseResults().get(task.getId()));
            }
            if (PipelineStage.STRUCTURE.name().equals(task.getStage())) {
                structureStage = true;
            }
            fillStats(stats, task, product, chunkSetByArtifact, embedSetByArtifact, stepAgg);
        }
        node.setStats(stats);
        // 组装统计在通用统计之后落位：通用 map 只覆盖切片与向量化等环节，而组装统计由本环节独占，
        // 放在它之前会被随后的空 map 覆盖掉。
        if (structureStage) {
            fillStructureStats(node, task, artifacts.documents().get(task.getId()));
        }
        // 预处理统计在通用统计之后并入：通用 map 里的 matched/changed 由 step_log 聚合而来，必须保留
        if (preprocessStage) {
            fillPreprocessStats(node, task, artifacts.views().get(task.getId()));
        }
        // 切片统计同样在通用统计之后并入：通用 map 里的 chunkCount 由集合表聚合而来，必须保留
        if (chunkStage) {
            fillChunkStats(node, task, artifacts);
        }
        return node;
    }

    /**
     * 切片任务 → 其上游预处理视图（"进入切片 / 跳过"元素数的口径来源）。
     *
     * <p>上游产物已在本批读过，这里只做索引，不再重复读对象。
     */
    private Map<Long, PreprocessView> upstreamViewsOf(List<WbPipelineTask> tasks,
                                                      Map<Long, Long> taskIdByProductId,
                                                      Map<Long, PreprocessView> viewByTaskId) {
        Map<Long, PreprocessView> views = new HashMap<>();
        for (WbPipelineTask task : tasks) {
            if (!PipelineStage.CHUNK.name().equals(task.getStage()) || NullUtil.isNull(task.getUpstreamProductId())) {
                continue;
            }
            Long upstreamTaskId = taskIdByProductId.get(task.getUpstreamProductId());
            PreprocessView view = NullUtil.isNull(upstreamTaskId) ? null : viewByTaskId.get(upstreamTaskId);
            if (NullUtil.isNotNull(view)) {
                views.put(task.getId(), view);
            }
        }
        return views;
    }

    /**
     * 批量读取某环节的产物本体：每个任务至多一次对象读取，读取失败记日志并按"无统计"处理。
     *
     * @param tasks       本次血缘的任务列表
     * @param productById 产物 ID → 产物行
     * @param stage       目标环节（只处理该环节的任务）
     * @param label       日志里的环节名
     * @param reader      产物行 → 产物本体（读不到返回 null）
     * @return 任务 ID → 产物本体（无产物或读取失败的任务不出现在结果里）
     */
    private <T> Map<Long, T> readProductBodies(List<WbPipelineTask> tasks,
                                               Map<Long, WbPipelineProduct> productById, PipelineStage stage,
                                               String label, Function<WbPipelineProduct, T> reader) {
        Map<Long, T> result = new HashMap<>();
        for (WbPipelineTask task : tasks) {
            if (!stage.name().equals(task.getStage()) || NullUtil.isNull(task.getProductId())) {
                continue;
            }
            WbPipelineProduct product = productById.get(task.getProductId());
            if (NullUtil.isNull(product) || StrUtil.isBlank(product.getArtifactId())) {
                continue;
            }
            T body = reader.apply(product);
            if (NullUtil.isNotNull(body)) {
                result.put(task.getId(), body);
            } else {
                log.warn("{}产物读取失败, taskId={}, artifactId={}", label, task.getId(), product.getArtifactId());
            }
        }
        return result;
    }

    /** 解析节点统计与摘要行一并回填：指标行与构成图取统计字段，摘要行按统计与状态陈述 */
    private void fillParseStats(LineageNodeVO node, WbPipelineTask task, ParseResult parseResult) {
        LineageParseStatsVO stats = ParseStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(), parseResult);
        node.setParseStats(stats);
        node.setParseSummary(ParseStatsSupport.summary(node.getErrorMsg(), stats, task.getStatus()));
    }

    /** 组装节点统计与摘要行一并回填：统计进通用 stats，摘要走通用 stageSummary */
    private void fillStructureStats(LineageNodeVO node, WbPipelineTask task, UnifiedDocument document) {
        Map<String, Object> stats =
                StructureStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(), document);
        node.setStageSummary(StructureStatsSupport.summary(node.getErrorMsg(), stats, task.getStatus()));
        if (NullUtil.isNotNull(stats)) {
            node.setStats(stats);
        }
    }

    /**
     * 预处理节点统计与摘要行一并回填：统计并入通用 stats（保留 step_log 聚合来的 matched/changed），
     * 摘要走通用 stageSummary。
     */
    private void fillPreprocessStats(LineageNodeVO node, WbPipelineTask task, PreprocessView view) {
        Map<String, Object> stats =
                PreprocessStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(), view);
        StatsSupport.mergeNodeStats(node, stats,
                PreprocessStatsSupport.summary(node.getErrorMsg(), stats, task.getStatus()));
    }

    /**
     * 切片节点统计与摘要行一并回填：统计并入通用 stats（保留集合表聚合来的 chunkCount），
     * 摘要走通用 stageSummary；片长上限与兜底参数取该次运行的策略快照。
     *
     * <p>快照缺失或损坏时策略取 null（不构造默认策略）：策略类口径整组留空，其余统计照常下发。
     */
    private void fillChunkStats(LineageNodeVO node, WbPipelineTask task, NodeArtifacts artifacts) {
        Map<String, Object> stats = ChunkStatsSupport.stats(task.getStartedAt(), task.getFinishedAt(),
                artifacts.chunkSets().get(task.getId()), chunkStrategyOf(task),
                artifacts.upstreamViews().get(task.getId()));
        StatsSupport.mergeNodeStats(node, stats,
                ChunkStatsSupport.summary(node.getErrorMsg(), stats, task.getStatus()));
    }

    /**
     * 任务策略快照 → 切片策略；缺失或损坏返回 null（不构造默认策略），策略类口径随之为空。
     *
     * @param task 切片任务行
     * @return 补全后的切片策略；缺失或损坏返回 null
     */
    private ChunkStrategy chunkStrategyOf(WbPipelineTask task) {
        try {
            return chunkStrategyParser.parse(task.getStrategySnapshot());
        } catch (Exception e) {
            log.warn("执行树切片节点策略快照缺失或损坏，策略类口径留空, taskId={}", task.getId(), e);
            return null;
        }
    }

    /** 统计摘要：CHUNK/EMBED 按 artifactId 匹配集合表；PREPROCESS 用 step_log 聚合 */
    private void fillStats(Map<String, Object> stats, WbPipelineTask task, WbPipelineProduct product,
                           Map<String, WbChunkSet> chunkSetByArtifact,
                           Map<String, WbEmbeddingSet> embedSetByArtifact,
                           Map<Long, long[]> stepAgg) {
        if (PipelineStage.CHUNK.name().equals(task.getStage())) {
            WbChunkSet chunkSet = chunkSetByArtifact.get(product.getArtifactId());
            if (NullUtil.isNotNull(chunkSet)) {
                stats.put("chunkCount", chunkSet.getChunkCount());
            }
        } else if (PipelineStage.EMBED.name().equals(task.getStage())) {
            WbEmbeddingSet embedSet = embedSetByArtifact.get(product.getArtifactId());
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

    /** 预处理统计聚合（step_log）：[0]=matchedCount 合计、[1]=changedCount 合计 */
    private Map<Long, long[]> aggregateStepLogs(List<WbPipelineTask> tasks) {
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

    /** 策略快照 → name-version 串；解析失败或字段缺失返回 null */
    private String resolveStrategyVersion(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            return null;
        }
        try {
            Map<String, Object> map = JsonUtil.toMap(snapshot);
            String name = String.valueOf(map.getOrDefault("name", ""));
            String version = String.valueOf(map.getOrDefault("version", ""));
            if (StrUtil.isBlank(name) || StrUtil.isBlank(version)) {
                return null;
            }
            return name + "-" + version;
        } catch (Exception e) {
            log.warn("策略快照解析失败, snapshot={}", StrUtil.maxLength(snapshot, 200));
            return null;
        }
    }

    /**
     * 能力快照 JSON 文本 → 结构化对象。
     *
     * <p>**解析成对象，不透传原文**：product.capabilitySnapshot 存的是
     * {@link CapabilitySnapshot} 序列化后的 JSON；解析在服务层完成，下发的就是可用字段
     * （与 {@link #resolveStrategyVersion} 同一口径）。
     *
     * <p>解析失败返回 null（**不回落原文**）：脏数据不继续往上层传。
     *
     * @param snapshot 产物上的能力快照 JSON 文本，可空
     * @return 结构化能力快照；快照为空或解析失败返回 null
     */
    private LineageCapabilityVO resolveCapability(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            return null;
        }
        try {
            CapabilitySnapshot src = JsonUtil.toObject(snapshot, CapabilitySnapshot.class);
            if (NullUtil.isNull(src)) {
                return null;
            }
            LineageCapabilityVO vo = new LineageCapabilityVO();
            vo.setParserName(src.getParserName());
            vo.setParserVersion(src.getParserVersion());
            vo.setOcr(toCapabilityRef(src.getOcr()));
            vo.setLayout(toCapabilityRef(src.getLayout()));
            vo.setTable(toCapabilityRef(src.getTable()));
            return vo;
        } catch (Exception e) {
            log.warn("能力快照解析失败, snapshot={}", StrUtil.maxLength(snapshot, 200));
            return null;
        }
    }

    /** 领域层能力引用 → 接口层能力引用；入参为空则返回空 */
    private LineageCapabilityVO.CapabilityRefVO toCapabilityRef(CapabilitySnapshot.CapabilityRef src) {
        if (NullUtil.isNull(src)) {
            return null;
        }
        LineageCapabilityVO.CapabilityRefVO vo = new LineageCapabilityVO.CapabilityRefVO();
        vo.setModel(src.getModel());
        vo.setVersion(src.getVersion());
        return vo;
    }

    private int stageOrder(String stage) {
        return switch (stage) {
            case "PARSE" -> 0;
            case "STRUCTURE" -> 1;
            case "PREPROCESS" -> 2;
            case "CHUNK" -> 3;
            case "EMBED" -> 4;
            default -> 5;
        };
    }
}
