package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.LineageQueryService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.artifact.LineageArtifactReader;
import com.workbuddy.biz.service.support.assemble.LineageNodeAssembler;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.lineage.LineageEdgeVO;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.dto.response.lineage.LineageVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 执行树聚合实现：批量 in 查询拼图（≈5 次 DB 访问），无 N+1。
 * 节点=一次运行；边=upstreamProductId 血缘反查（task.productId → product.id → 产出任务）；
 * 节点/边排序与访问校验在本类，产物批量读取在 {@link LineageArtifactReader}，
 * 统计与摘要组装在 {@link LineageNodeAssembler}。
 */
@Service
@RequiredArgsConstructor
public class LineageQueryServiceImpl implements LineageQueryService {

    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final WbPipelineProductDbService pipelineProductDbService;
    private final WbPipelineStepLogDbService stepLogDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbEmbeddingSetDbService embeddingSetDbService;
    private final FileStorage fileStorage;
    private final FileResultAccessGuard accessGuard;
    private final ChunkStrategyParser chunkStrategyParser;

    /**
     * 文件血缘：从解析到索引的环节节点与依赖边。
     *
     * @param fileResultId 文件结果 ID
     * @return 血缘视图
     */
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

        Map<Long, long[]> stepAgg = LineageNodeAssembler.aggregateStepLogs(tasks, stepLogDbService);
        LineageArtifactReader.NodeArtifacts artifacts = LineageArtifactReader.read(tasks, productById,
                taskIdByProductId, fileResultId, fileStorage, chunkSetDbService, embeddingSetDbService);

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
            WbPipelineProduct product =
                    NullUtil.isNull(task.getProductId()) ? null : productById.get(task.getProductId());
            nodes.add(LineageNodeAssembler.toNode(task, product, stepAgg, artifacts, chunkStrategyParser));
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
