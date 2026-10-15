package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.ModelAdminService;
import com.workbuddy.common.dto.request.model.ModelQueryDto;
import com.workbuddy.common.dto.request.model.ModelSaveDto;
import com.workbuddy.common.dto.request.model.ModelStatusDto;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.response.model.ModelItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型管理 Controller：模型目录的清单、新增、编辑与启停，对所有登录用户开放。
 *
 * <p>路径前缀 `/model`（与用量两个只读接口同前缀，仓库既有接口不带 `api` 段）。
 * 厂商候选与展示名由前端枚举承载（与配置 `knowledge.model.vendors` 的键保持一致），
 * 厂商连接与密钥也都在配置里，本 Controller **没有密钥相关的入口**。
 *
 * <p>查询条件成组时收进 {@link ModelQueryDto} 并用 `@ParameterObject` 展开成独立查询参数。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/model")
@Tag(name = "模型管理", description = "模型目录的清单、写入与启停（对所有登录用户开放）")
public class ModelAdminController {

    private final ModelAdminService modelAdminService;

    @GetMapping("/models")
    @Operation(summary = "模型清单",
            description = "按能力（字典名）/ 状态（ENABLED / DISABLED）/ 关键字（匹配模型名或展示名）过滤，"
                    + "返回目录视图字段：厂商、模型、展示名、能力多值、上下文窗口、四个价格列、币种、两个限额、"
                    + "状态、生效区间、最近一次连通性测试结果与备注")
    public R<List<ModelItemVO>> models(@ParameterObject ModelQueryDto query) {
        return R.ok(modelAdminService.list(query));
    }

    @PostMapping("/models")
    @Operation(summary = "新增模型",
            description = "校验厂商码在配置里、唯一键 (vendor_code, model_key) 不冲突、价格与限额非负、"
                    + "能力与状态取值在字典内、生效区间开始早于结束；展示名留空取模型名")
    public R<ModelItemVO> create(@RequestBody ModelSaveDto dto) {
        return R.ok(modelAdminService.create(dto), "新增成功");
    }

    @PutMapping("/models/{id}")
    @Operation(summary = "编辑模型",
            description = "覆盖写业务列；校验口径与新增一致，唯一键判定排除本行；价格与限额清空即写空")
    public R<ModelItemVO> update(@PathVariable Long id, @RequestBody ModelSaveDto dto) {
        return R.ok(modelAdminService.update(id, dto), "保存成功");
    }

    @PatchMapping("/models/{id}/status")
    @Operation(summary = "启停模型", description = "只改状态列，取值 ENABLED / DISABLED")
    public R<ModelItemVO> updateStatus(@PathVariable Long id, @RequestBody ModelStatusDto dto) {
        return R.ok(modelAdminService.updateStatus(id, dto), "状态已更新");
    }
}
