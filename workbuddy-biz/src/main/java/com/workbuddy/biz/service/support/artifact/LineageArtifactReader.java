package com.workbuddy.biz.service.support.artifact;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.biz.service.support.stats.ParseStatsSupport;
import com.workbuddy.biz.service.support.stats.StructureStatsSupport;
import com.workbuddy.biz.service.support.stats.PreprocessStatsSupport;
import com.workbuddy.biz.service.support.stats.ChunkStatsSupport;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行树产物读取器：批量读切片/向量集合行与解析/组装/预处理/切片产物本体。
 *
 * <p>每个任务至多一次对象读取；产物本体读取失败记日志并按"无统计"处理，集合行读取失败原样抛出。
 */
@Slf4j
public final class LineageArtifactReader {

    private LineageArtifactReader() {
    }

    /**
     * 按任务索引的产物：集合行按产物键索引，产物本体按任务 ID 索引。
     *
     * @param chunkSetByArtifact 产物键 → 切片集合行
     * @param embedSetByArtifact 产物键 → 向量集合行
     * @param parseResults       任务 ID → 解析产物本体
     * @param documents          任务 ID → 统一文档
     * @param views              任务 ID → 预处理视图
     * @param chunkSets          任务 ID → 切片集合本体
     * @param upstreamViews      任务 ID → 上游预处理视图
     */
    public record NodeArtifacts(Map<String, WbChunkSet> chunkSetByArtifact,
                                Map<String, WbEmbeddingSet> embedSetByArtifact,
                                Map<Long, ParseResult> parseResults,
                                Map<Long, UnifiedDocument> documents,
                                Map<Long, PreprocessView> views,
                                Map<Long, ChunkSet> chunkSets,
                                Map<Long, PreprocessView> upstreamViews) {
    }

    /**
     * 读取执行树所需产物。
     *
     * @param tasks                本次血缘的任务列表
     * @param productById          产物 ID → 产物行
     * @param taskIdByProductId    产物 ID → 产出任务 ID
     * @param fileResultId         文件结果 ID（集合行按文件取）
     * @param fileStorage          对象存储
     * @param chunkSetDbService    切片集合数据访问
     * @param embeddingSetDbService 向量集合数据访问
     * @return 按任务与产物键索引的产物集合
     */
    public static NodeArtifacts read(List<WbPipelineTask> tasks, Map<Long, WbPipelineProduct> productById,
                                     Map<Long, Long> taskIdByProductId, Long fileResultId,
                                     FileStorage fileStorage, WbChunkSetDbService chunkSetDbService,
                                     WbEmbeddingSetDbService embeddingSetDbService) {
        Map<String, WbChunkSet> chunkSetByArtifact = chunkSetDbService.listByFileResultId(fileResultId).stream()
                .collect(Collectors.toMap(WbChunkSet::getArtifactId, s -> s, (a, b) -> a));
        Map<String, WbEmbeddingSet> embedSetByArtifact = embeddingSetDbService.listByFileResultId(fileResultId)
                .stream()
                .collect(Collectors.toMap(WbEmbeddingSet::getArtifactId, s -> s, (a, b) -> a));
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
        return new NodeArtifacts(chunkSetByArtifact, embedSetByArtifact, parseResultByTaskId, documentByTaskId,
                viewByTaskId, chunkSetByTaskId, upstreamViewByTaskId);
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
    private static <T> Map<Long, T> readProductBodies(List<WbPipelineTask> tasks,
                                                      Map<Long, WbPipelineProduct> productById,
                                                      PipelineStage stage, String label,
                                                      Function<WbPipelineProduct, T> reader) {
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

    /**
     * 切片任务 → 其上游预处理视图（"进入切片 / 跳过"元素数的口径来源）。
     *
     * <p>上游产物已在本批读过，这里只做索引，不再重复读对象。
     */
    private static Map<Long, PreprocessView> upstreamViewsOf(List<WbPipelineTask> tasks,
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
}
