package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.PreprocessControlService;
import com.workbuddy.common.dto.request.stage.StageDetailQueryDto;
import com.workbuddy.common.dto.request.stage.StageTriggerQueryDto;
import com.workbuddy.common.dto.response.R;
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
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
public class KnowledgeFilePreprocessController {

    private final PreprocessControlService preprocessControlService;

    @PostMapping("/{fileResultId}/preprocess")
    public R<PreprocessTriggerVO> preprocess(
            @PathVariable("fileResultId") Long fileResultId,
            StageTriggerQueryDto query) {
        log.info("===> KnowledgeFilePreprocessController preprocess 触发预处理, fileResultId={}, strategyVersionId={}, upstreamProductId={}",
                fileResultId, query.getStrategyVersionId(), query.getUpstreamProductId());
        return R.ok(preprocessControlService.preprocess(fileResultId, query.getStrategyVersionId(),
                query.getUpstreamProductId()));
    }

    @GetMapping("/{fileResultId}/preprocess-detail")
    public R<PreprocessDetailVO> preprocessDetail(
            @PathVariable("fileResultId") Long fileResultId,
            StageDetailQueryDto query) {
        return R.ok(preprocessControlService.preprocessDetail(fileResultId, query.getTaskId()));
    }
}
