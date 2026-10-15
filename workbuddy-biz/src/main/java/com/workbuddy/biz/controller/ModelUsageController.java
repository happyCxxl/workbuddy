package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.ModelUsageService;
import com.workbuddy.common.domain.model.calllog.ModelCallLogSummary;
import com.workbuddy.common.domain.model.calllog.ModelCallLogView;
import com.workbuddy.common.dto.request.model.ModelUsageQueryDto;
import com.workbuddy.common.dto.response.R;
import lombok.RequiredArgsConstructor;
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
 * <p>查询对象收参（{@link ModelUsageQueryDto}）：DTO 字段按同名的独立查询参数绑定。
 *
 * @author cxxl
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/model/usage")
public class ModelUsageController {

    private final ModelUsageService modelUsageService;

    @GetMapping("/summary")
    public R<ModelCallLogSummary> summary(ModelUsageQueryDto query) {
        return R.ok(modelUsageService.summary(query));
    }

    @GetMapping("/calls")
    public R<List<ModelCallLogView>> calls(ModelUsageQueryDto query) {
        return R.ok(modelUsageService.calls(query));
    }
}
