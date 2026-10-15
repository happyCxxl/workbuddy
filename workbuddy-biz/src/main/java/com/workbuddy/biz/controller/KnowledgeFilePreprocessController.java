package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.PreprocessControlService;
import com.workbuddy.common.dto.request.stage.StageDetailQueryDto;
import com.workbuddy.common.dto.request.stage.StageTriggerQueryDto;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.response.preprocess.PreprocessDetailVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessTriggerVO;
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
 * 预处理控制面 Controller（手动逐环节触发点）。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
@Tag(name = "预处理控制", description = "触发预处理与重跑")
public class KnowledgeFilePreprocessController {

    private final PreprocessControlService preprocessControlService;

    @PostMapping("/{fileResultId}/preprocess")
    @Operation(summary = "触发预处理", description = "触发预处理（手动逐环节）；"
            + "strategyVersionId 可选（策略行 ID，不传用知识库绑定，两档都不命中 40438）；"
            + "upstreamProductId 必填（指定上游 STRUCTURE 产物，未指定或传 0 报 40436）；"
            + "已有任务一律 40431；QUEUED 补投归启动补偿与低频扫描")
    public R<PreprocessTriggerVO> preprocess(
            @Parameter(description = "文件结果ID", required = true) @PathVariable("fileResultId") Long fileResultId,
            @ParameterObject StageTriggerQueryDto query) {
        log.info("===> KnowledgeFilePreprocessController preprocess 触发预处理, fileResultId={}, strategyVersionId={}, upstreamProductId={}",
                fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId());
        return R.ok(preprocessControlService.preprocess(fileResultId, query.getStrategyVersionId(),
                query.getUpstreamProductId()));
    }

    @GetMapping("/{fileResultId}/preprocess-detail")
    @Operation(summary = "预处理详情", description = "任务状态 + 子步骤列表 + 策略信息 + 预处理统计/视图元素/产物引用；taskId 可选（缺省取最新任务，传了则查该次运行）")
    public R<PreprocessDetailVO> preprocessDetail(
            @Parameter(description = "文件结果ID", required = true) @PathVariable("fileResultId") Long fileResultId,
            @ParameterObject StageDetailQueryDto query) {
        return R.ok(preprocessControlService.preprocessDetail(fileResultId, query.getTaskId()));
    }
}
