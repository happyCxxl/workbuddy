package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.ChunkControlService;
import com.workbuddy.common.dto.request.stage.StageDetailQueryDto;
import com.workbuddy.common.dto.request.stage.StageTriggerQueryDto;
import com.workbuddy.common.dto.response.R;
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
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
public class KnowledgeFileChunkController {

    private final ChunkControlService chunkControlService;

    @PostMapping("/{fileResultId}/chunk")
    public R<ChunkTriggerVO> chunk(
            @PathVariable("fileResultId") Long fileResultId,
            StageTriggerQueryDto query) {
        log.info("===> KnowledgeFileChunkController chunk 触发切片, fileResultId={}, strategyVersionId={}, upstreamProductId={}",
                fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId());
        return R.ok(chunkControlService.chunk(fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId()));
    }

    @GetMapping("/{fileResultId}/chunk-detail")
    public R<ChunkDetailVO> chunkDetail(
            @PathVariable("fileResultId") Long fileResultId,
            StageDetailQueryDto query) {
        return R.ok(chunkControlService.chunkDetail(fileResultId, query.getTaskId()));
    }
}
