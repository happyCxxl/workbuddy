package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.StructureControlService;
import com.workbuddy.common.dto.request.stage.StageDetailQueryDto;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.response.structure.StructureDetailVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结构组装控制面 Controller（组装环节触发点）。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
@Tag(name = "结构组装控制", description = "触发统一结构组装与重跑")
public class KnowledgeFileStructureController {

    private final StructureControlService structureControlService;

    @PostMapping("/{fileResultId}/structure")
    @Operation(summary = "触发组装", description = "触发统一组装/重跑（手动逐环节）；upstreamProductId 必填（指定上游解析产物，未指定或传 0 报 40436）；已有任务一律 40431；QUEUED 补投归启动补偿与低频扫描")
    public R<StageTriggerVO> structure(
            @Parameter(description = "文件结果ID", required = true) @PathVariable("fileResultId") Long fileResultId,
            @Parameter(description = "上游产物ID（必填，指定 PARSE 产物）") @RequestParam(value = "upstreamProductId", required = false) Long upstreamProductId) {
        log.info("===> KnowledgeFileStructureController structure 触发组装, fileResultId={}, upstreamProductId={}",
                fileResultId, upstreamProductId);
        return R.ok(structureControlService.structure(fileResultId, upstreamProductId));
    }

    @GetMapping("/{fileResultId}/structure-detail")
    @Operation(summary = "组装详情", description = "任务状态 + 子步骤列表 + 组装统计/冲突/章节/产物引用；taskId 可选（缺省取最新任务，传了则查该次运行）")
    public R<StructureDetailVO> structureDetail(
            @Parameter(description = "文件结果ID", required = true) @PathVariable("fileResultId") Long fileResultId,
            @ParameterObject StageDetailQueryDto query) {
        return R.ok(structureControlService.structureDetail(fileResultId, query.getTaskId()));
    }
}
