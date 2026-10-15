package com.workbuddy.biz.controller;

import com.workbuddy.common.dto.response.R;
import com.workbuddy.biz.service.LineageQueryService;
import com.workbuddy.common.dto.response.lineage.LineageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "执行树聚合", description = "文件运行血缘树（链图数据源）")
public class LineageController {

    private final LineageQueryService lineageQueryService;

    @GetMapping("/{fileResultId}/lineage")
    @Operation(summary = "执行树聚合", description = "该文件全部运行节点（环节顺序）+ 血缘边（upstreamProductId 反查）+ 统计摘要；文件结果不存在 40432")
    public R<LineageVO> lineage(
            @Parameter(description = "文件结果ID", required = true) @PathVariable("fileResultId") Long fileResultId) {
        return R.ok(lineageQueryService.lineage(fileResultId));
    }
}
