package com.workbuddy.biz.controller.model;

import com.workbuddy.biz.service.ModelAdminService;
import com.workbuddy.common.dto.request.model.ModelQueryDto;
import com.workbuddy.common.dto.request.model.ModelSaveDto;
import com.workbuddy.common.dto.request.model.ModelStatusDto;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.response.model.ModelItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>查询条件成组时收进 {@link ModelQueryDto}，DTO 字段按同名的独立查询参数绑定。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/model")
public class ModelController {

    private final ModelAdminService modelAdminService;

    @GetMapping("/models")
    public R<List<ModelItemVO>> models(ModelQueryDto query) {
        return R.ok(modelAdminService.list(query));
    }

    @PostMapping("/models")
    public R<ModelItemVO> create(@RequestBody ModelSaveDto dto) {
        return R.ok(modelAdminService.create(dto), "新增成功");
    }

    @PutMapping("/models/{id}")
    public R<ModelItemVO> update(@PathVariable Long id, @RequestBody ModelSaveDto dto) {
        return R.ok(modelAdminService.update(id, dto), "保存成功");
    }

    @PatchMapping("/models/{id}/status")
    public R<ModelItemVO> updateStatus(@PathVariable Long id, @RequestBody ModelStatusDto dto) {
        return R.ok(modelAdminService.updateStatus(id, dto), "状态已更新");
    }
}
