package com.workbuddy.biz.controller;

import com.workbuddy.common.dto.request.stage.StageDetailQueryDto;
import com.workbuddy.common.dto.request.stage.StageTriggerQueryDto;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.biz.service.EmbedControlService;
import com.workbuddy.common.dto.response.embed.EmbedDetailVO;
import com.workbuddy.common.dto.response.embed.EmbedTriggerVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
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
@Tag(name = "向量化控制", description = "触发向量化与重跑")
public class KnowledgeFileEmbedController {

    private final EmbedControlService embedControlService;

    @PostMapping("/{fileResultId}/embed")
    @Operation(summary = "触发向量化", description = "触发向量化/重跑（手动逐环节）；"
            + "strategyVersionId 可选（策略行 ID，不传用知识库绑定，两档都不命中 40438）；upstreamProductId 必填（指定上游 CHUNK 产物，未指定或传 0 报 40436）；"
            + "窗口不兼容 40435、已有任务一律 40431；QUEUED 补投归启动补偿与低频扫描")
    public R<EmbedTriggerVO> embed(
            @Parameter(description = "文件结果ID", required = true) @PathVariable("fileResultId") Long fileResultId,
            @ParameterObject StageTriggerQueryDto query) {
        log.info("===> KnowledgeFileEmbedController embed 触发向量化, fileResultId={}, strategyVersionId={}, upstreamProductId={}",
                fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId());
        return R.ok(embedControlService.embed(fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId()));
    }

    @GetMapping("/{fileResultId}/embed-detail")
    @Operation(summary = "向量化详情", description = "任务状态 + 子步骤列表 + 集合摘要/记录 + 产物引用"
            + "（wb_knowledge_embedding_set/wb_knowledge_embedding_record 数据源，向量本体不下发）；taskId 可选（缺省取最新任务）")
    public R<EmbedDetailVO> embedDetail(
            @Parameter(description = "文件结果ID", required = true) @PathVariable("fileResultId") Long fileResultId,
            @ParameterObject StageDetailQueryDto query) {
        return R.ok(embedControlService.embedDetail(fileResultId, query.getTaskId()));
    }
}
