package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.ChunkControlService;
import com.workbuddy.common.dto.request.stage.StageDetailQueryDTO;
import com.workbuddy.common.dto.request.stage.StageTriggerQueryDTO;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.response.chunk.ChunkDetailVO;
import com.workbuddy.common.dto.response.chunk.ChunkTriggerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 切片控制面 Controller（手动逐环节触发点）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
public class KnowledgeFileChunkController {

    private final ChunkControlService chunkControlService;

    /**
     * 触发切片：登记 CHUNK 任务并按上游预处理产物出队执行。
     *
     * @param fileResultId 文件结果 ID
     * @param query        触发参数（策略版本与上游产物，可空）
     * @return 任务 ID 与生效策略版本
     */
    @PostMapping("/{fileResultId}/chunk")
    public R<ChunkTriggerVO> chunk(
            @PathVariable("fileResultId") Long fileResultId,
            StageTriggerQueryDTO query) {
        log.info("===> KnowledgeFileChunkController chunk 触发切片, fileResultId={}, strategyVersionId={},"
                        + " upstreamProductId={}",
                fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId());
        return R.ok(chunkControlService.chunk(fileResultId, query.getStrategyVersionId(),
                query.getUpstreamProductId()));
    }

    /**
     * 切片详情：任务状态、子步骤、切片统计与列表、产物引用。
     *
     * @param fileResultId 文件结果 ID
     * @param query        查询参数（任务 ID，可空表示取最新）
     * @return 切片详情
     */
    @GetMapping("/{fileResultId}/chunk-detail")
    public R<ChunkDetailVO> chunkDetail(
            @PathVariable("fileResultId") Long fileResultId,
            StageDetailQueryDTO query) {
        return R.ok(chunkControlService.chunkDetail(fileResultId, query.getTaskId()));
    }
}
