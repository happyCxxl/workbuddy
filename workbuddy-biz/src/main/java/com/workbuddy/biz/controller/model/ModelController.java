package com.workbuddy.biz.controller.model;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.biz.service.ModelService;
import com.workbuddy.common.dto.request.model.ModelQueryDTO;
import com.workbuddy.common.dto.request.model.ModelSaveDTO;
import com.workbuddy.common.dto.request.model.ModelStatusDTO;
import com.workbuddy.common.dto.request.model.ModelUpdateDTO;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.response.model.ModelItemVO;
import jakarta.validation.Valid;
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

/**
 * 模型管理 Controller：模型目录的清单分页、新增、编辑与启停，对所有登录用户开放。
 *
 * <p>路径前缀 `/model`（与用量两个只读接口同前缀，仓库既有接口不带 `api` 段）。
 * 厂商候选由前端枚举承载（与配置 `workbuddy.model.vendors` 的键保持一致），展示名取配置的 display-name，
 * 厂商连接与密钥也都在配置里，本 Controller **没有密钥相关的入口**。
 *
 * <p>查询条件成组时收进 {@link ModelQueryDTO}（页码与页长口径见其父类），DTO 字段按同名的独立查询参数绑定。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/model")
public class ModelController {

    private final ModelService modelService;

    @GetMapping("/page")
    public R<IPage<ModelItemVO>> page(@Valid ModelQueryDTO query) {
        return R.ok(modelService.page(query));
    }

    @PostMapping("/create")
    public R<ModelItemVO> create(@Valid @RequestBody ModelSaveDTO dto) {
        return R.ok(modelService.create(dto), "新增成功");
    }

    @PutMapping("/update/{id}")
    public R<ModelItemVO> update(@PathVariable Long id, @Valid @RequestBody ModelUpdateDTO dto) {
        return R.ok(modelService.update(id, dto), "更新成功");
    }

    @PatchMapping("/updateStatus/{id}")
    public R<ModelItemVO> updateStatus(@PathVariable Long id, @Valid @RequestBody ModelStatusDTO dto) {
        return R.ok(modelService.updateStatus(id, dto), "状态已更新");
    }
}
