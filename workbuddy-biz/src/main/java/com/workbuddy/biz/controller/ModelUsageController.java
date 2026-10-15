package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.ModelUsageService;
import com.workbuddy.common.domain.model.calllog.ModelCallLogSummary;
import com.workbuddy.common.domain.model.calllog.ModelCallLogView;
import com.workbuddy.common.dto.request.model.ModelUsageQueryDto;
import com.workbuddy.common.dto.response.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型用量 Controller：调用明细查询与用量成本概览，两个接口都只读、对所有登录用户开放。
 *
 * <p>路径前缀与模型管理接口同源（`/model`）：仓库既有接口不带 `api` 段，本 Controller 也不带。
 * 概览与明细共用同一组查询条件（时间范围必填 + 租户 / 能力 / 模型 / 路由原因 / 状态可选），
 * 概览不做日聚合表，直接由明细表聚合（D16）。
 *
 * <p>查询对象收参（{@link ModelUsageQueryDto}）+ `@ParameterObject`：Swagger 把对象字段展开成独立查询参数。
 *
 * @author cxxl
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/model/usage")
@Tag(name = "模型用量", description = "调用明细查询与用量成本概览（对所有登录用户开放）")
public class ModelUsageController {

    private final ModelUsageService modelUsageService;

    @GetMapping("/summary")
    @Operation(summary = "用量与成本概览",
            description = "时间范围必填（from 含 / to 不含，yyyy-MM-dd 或 yyyy-MM-ddTHH:mm:ss）；"
                    + "返回调用总数、成功失败数、超时与限流数、未知用量条数与占比、已知 token 与成本合计；"
                    + "未知用量行按 NULL 记录，不参与合计")
    public R<ModelCallLogSummary> summary(@ParameterObject ModelUsageQueryDto query) {
        return R.ok(modelUsageService.summary(query));
    }

    @GetMapping("/calls")
    @Operation(summary = "调用明细查询",
            description = "时间范围必填；可选租户 / 能力 / 模型 / 路由原因 / 状态；按调用时间倒序，"
                    + "条数由 limit 收口（未传 500，上限 2000）")
    public R<List<ModelCallLogView>> calls(@ParameterObject ModelUsageQueryDto query) {
        return R.ok(modelUsageService.calls(query));
    }
}
