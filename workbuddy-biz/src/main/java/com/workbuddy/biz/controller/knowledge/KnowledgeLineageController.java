package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.common.dto.response.R;
import com.workbuddy.biz.service.LineageQueryService;
import com.workbuddy.common.dto.response.lineage.LineageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行树聚合 Controller：链图数据源，纯读聚合。
 *
 * @author cxxl
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
public class KnowledgeLineageController {

    private final LineageQueryService lineageQueryService;

    @GetMapping("/{fileResultId}/lineage")
    public R<LineageVO> lineage(
            @PathVariable("fileResultId") Long fileResultId) {
        return R.ok(lineageQueryService.lineage(fileResultId));
    }
}
