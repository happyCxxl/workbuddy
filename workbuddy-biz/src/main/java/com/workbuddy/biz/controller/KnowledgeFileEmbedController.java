package com.workbuddy.biz.controller;

import com.workbuddy.common.dto.request.stage.StageDetailQueryDto;
import com.workbuddy.common.dto.request.stage.StageTriggerQueryDto;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.biz.service.EmbedControlService;
import com.workbuddy.common.dto.response.embed.EmbedDetailVO;
import com.workbuddy.common.dto.response.embed.EmbedTriggerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向量化控制面 Controller（手动逐环节触发点）。
 * 接口全部走平台资源服务器鉴权（需 Bearer token）。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
public class KnowledgeFileEmbedController {

    private final EmbedControlService embedControlService;

    @PostMapping("/{fileResultId}/embed")
    public R<EmbedTriggerVO> embed(
            @PathVariable("fileResultId") Long fileResultId,
            StageTriggerQueryDto query) {
        log.info("===> KnowledgeFileEmbedController embed 触发向量化, fileResultId={}, strategyVersionId={}, upstreamProductId={}",
                fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId());
        return R.ok(embedControlService.embed(fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId()));
    }

    @GetMapping("/{fileResultId}/embed-detail")
    public R<EmbedDetailVO> embedDetail(
            @PathVariable("fileResultId") Long fileResultId,
            StageDetailQueryDto query) {
        return R.ok(embedControlService.embedDetail(fileResultId, query.getTaskId()));
    }
}
