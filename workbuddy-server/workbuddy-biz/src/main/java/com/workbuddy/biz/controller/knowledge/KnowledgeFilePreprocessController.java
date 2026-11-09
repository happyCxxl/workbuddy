package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.PreprocessControlService;
import com.workbuddy.common.dto.request.stage.StageDetailQueryDTO;
import com.workbuddy.common.dto.request.stage.StageTriggerQueryDTO;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.response.preprocess.PreprocessDetailVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessTriggerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预处理控制面 Controller（手动逐环节触发点）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge-files")
public class KnowledgeFilePreprocessController {

    private final PreprocessControlService preprocessControlService;

    /**
     * 触发预处理：登记 PREPROCESS 任务并按上游解析产物出队执行。
     *
     * @param knowledgeFileId 知识库文件 ID
     * @param query        触发参数（策略版本与上游产物，可空）
     * @return 任务 ID 与生效策略版本
     */
    @PostMapping("/{knowledgeFileId}/preprocess")
    public R<PreprocessTriggerVO> preprocess(
            @PathVariable("knowledgeFileId") Long knowledgeFileId,
            StageTriggerQueryDTO query) {
        log.info("===> KnowledgeFilePreprocessController preprocess 触发预处理, knowledgeFileId={},"
                        + " strategyVersionId={}, upstreamProductId={}",
                knowledgeFileId, query.getStrategyVersionId(), query.getUpstreamProductId());
        return R.ok(preprocessControlService.preprocess(knowledgeFileId, query.getStrategyVersionId(),
                query.getUpstreamProductId()));
    }

    /**
     * 预处理详情：任务状态、子步骤、字段与剔除统计、产物引用。
     *
     * @param knowledgeFileId 知识库文件 ID
     * @param query        查询参数（任务 ID，可空表示取最新）
     * @return 预处理详情
     */
    @GetMapping("/{knowledgeFileId}/preprocess-detail")
    public R<PreprocessDetailVO> preprocessDetail(
            @PathVariable("knowledgeFileId") Long knowledgeFileId,
            StageDetailQueryDTO query) {
        return R.ok(preprocessControlService.preprocessDetail(knowledgeFileId, query.getTaskId()));
    }
}
