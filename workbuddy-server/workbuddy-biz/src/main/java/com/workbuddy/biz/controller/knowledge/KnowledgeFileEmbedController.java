package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.common.dto.request.stage.StageDetailQueryDTO;
import com.workbuddy.common.dto.request.stage.StageTriggerQueryDTO;
import com.workbuddy.common.web.R;
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
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
public class KnowledgeFileEmbedController {

    private final EmbedControlService embedControlService;

    /**
     * 触发向量化：登记 EMBED 任务并按上游切片产物出队执行。
     *
     * @param fileResultId 文件结果 ID
     * @param query        触发参数（策略版本与上游产物，可空）
     * @return 任务 ID 与生效策略版本
     */
    @PostMapping("/{fileResultId}/embed")
    public R<EmbedTriggerVO> embed(
            @PathVariable("fileResultId") Long fileResultId,
            StageTriggerQueryDTO query) {
        log.info("===> KnowledgeFileEmbedController embed 触发向量化, fileResultId={}, strategyVersionId={},"
                        + " upstreamProductId={}",
                fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId());
        return R.ok(embedControlService.embed(fileResultId, query.getStrategyVersionId(),
                query.getUpstreamProductId()));
    }

    /**
     * 向量化详情：任务状态、子步骤、向量统计与记录、产物引用。
     *
     * @param fileResultId 文件结果 ID
     * @param query        查询参数（任务 ID，可空表示取最新）
     * @return 向量化详情
     */
    @GetMapping("/{fileResultId}/embed-detail")
    public R<EmbedDetailVO> embedDetail(
            @PathVariable("fileResultId") Long fileResultId,
            StageDetailQueryDTO query) {
        return R.ok(embedControlService.embedDetail(fileResultId, query.getTaskId()));
    }
}
