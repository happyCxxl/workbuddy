package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.ParseControlService;
import com.workbuddy.common.dto.request.stage.StageDetailQueryDTO;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.response.parse.ParseDetailVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 解析环节 Controller：触发解析 + 解析详情（解析环节专属）。
 *
 * <p>各环节共用的产物内容在 {@link KnowledgeFileStageController}。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
public class KnowledgeFileParseController {

    private final ParseControlService parseControlService;

    /**
     * 触发解析（手动逐环节，链路起点）。
     *
     * <p>执行键 = 文件结果（解析没有上游产物与策略版本）：已有任务（任何状态，含执行中）一律拒绝 40431，
     * 没有才新建 PARSE 任务并入队。完成后停在终态，不自动触发组装。
     *
     * @param fileResultId 文件结果 ID（路径参数）
     * @return 触发响应（新建任务 ID）
     * @apiNote 文件结果不存在 40432；已有任务（含执行中）40431
     */
    @PostMapping("/{fileResultId}/parse")
    public R<StageTriggerVO> parse(
            @PathVariable("fileResultId") Long fileResultId) {
        log.info("===> KnowledgeFileParseController parse 触发解析, fileResultId={}", fileResultId);
        return R.ok(parseControlService.parse(fileResultId));
    }

    /**
     * 解析详情：任务状态 + 子步骤列表 + 产物引用/告警/统计（告警与统计从产物 JSON 汇总）。
     *
     * @param fileResultId 文件结果 ID（路径参数）
     * @param query        查询条件：taskId 可选（缺省取最新任务，传了则查该次历史运行）
     * @return 解析详情 VO（无任务时 taskId/步骤/统计为空，告警为空列表）
     */
    @GetMapping("/{fileResultId}/parse-detail")
    public R<ParseDetailVO> parseDetail(
            @PathVariable("fileResultId") Long fileResultId,
            StageDetailQueryDTO query) {
        return R.ok(parseControlService.parseDetail(fileResultId, query.getTaskId()));
    }
}
