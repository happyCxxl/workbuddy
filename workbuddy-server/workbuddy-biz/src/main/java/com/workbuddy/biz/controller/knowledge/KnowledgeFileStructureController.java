package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.StructureControlService;
import com.workbuddy.common.dto.request.stage.StageDetailQueryDTO;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.response.structure.StructureDetailVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结构组装控制面 Controller（组装环节触发点）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
public class KnowledgeFileStructureController {

    private final StructureControlService structureControlService;

    /**
     * 触发结构化组装：登记 STRUCTURE 任务并按上游预处理产物出队执行。
     *
     * @param fileResultId      文件结果 ID
     * @param upstreamProductId 上游预处理产物 ID（可空）
     * @return 任务 ID 与生效策略版本
     */
    @PostMapping("/{fileResultId}/structure")
    public R<StageTriggerVO> structure(
            @PathVariable("fileResultId") Long fileResultId,
            @RequestParam(value = "upstreamProductId", required = false) Long upstreamProductId) {
        log.info("===> KnowledgeFileStructureController structure 触发组装, fileResultId={}, upstreamProductId={}",
                fileResultId, upstreamProductId);
        return R.ok(structureControlService.structure(fileResultId, upstreamProductId));
    }

    /**
     * 结构化详情：任务状态、子步骤、统计与大纲、产物引用。
     *
     * @param fileResultId 文件结果 ID
     * @param query        查询参数（任务 ID，可空表示取最新）
     * @return 结构化详情
     */
    @GetMapping("/{fileResultId}/structure-detail")
    public R<StructureDetailVO> structureDetail(
            @PathVariable("fileResultId") Long fileResultId,
            StageDetailQueryDTO query) {
        return R.ok(structureControlService.structureDetail(fileResultId, query.getTaskId()));
    }
}
