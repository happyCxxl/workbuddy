package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDTO;
import com.workbuddy.common.dto.request.strategy.StrategyVersionUpdateDTO;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 策略版本管理 Controller：列表（下拉/管理）+ 创建/编辑（复制新行）/启停/删除（有绑定引用禁删）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/strategy-versions")
public class KnowledgeStrategyController {

    private final StrategyVersionService strategyVersionService;

    /**
     * 列出某策略类型的版本。
     *
     * @param type            策略类型码值
     * @param includeInactive 是否包含停用版本
     * @return 策略版本列表
     */
    @GetMapping
    public R<List<StrategyVersionVO>> list(
            @RequestParam("type") String type,
            @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return R.ok(strategyVersionService.list(type, includeInactive));
    }

    /**
     * 新建策略版本。
     *
     * @param dto 新建请求
     * @return 新建的策略版本
     */
    @PostMapping
    public R<StrategyVersionVO> create(@Valid @RequestBody StrategyVersionCreateDTO dto) {
        log.info("===> StrategyVersionController create 创建策略版本, type={}, name={}, version={}",
                dto.getType(), dto.getName(), dto.getVersion());
        return R.ok(strategyVersionService.create(dto), "创建成功");
    }

    /**
     * 编辑策略版本。
     *
     * @param id  策略版本行 ID
     * @param dto 编辑请求
     * @return 更新后的策略版本
     */
    @PutMapping("/{id}")
    public R<StrategyVersionVO> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody StrategyVersionUpdateDTO dto) {
        log.info("===> StrategyVersionController update 编辑策略版本, id={}", id);
        return R.ok(strategyVersionService.update(id, dto), "更新成功");
    }

    /**
     * 启用策略版本。
     *
     * @param id 策略版本行 ID
     * @return 更新后的策略版本
     */
    @PostMapping("/{id}/enable")
    public R<StrategyVersionVO> enable(
            @PathVariable("id") Long id) {
        log.info("===> StrategyVersionController enable 启用策略版本, id={}", id);
        return R.ok(strategyVersionService.enable(id), "启用成功");
    }

    /**
     * 停用策略版本。
     *
     * @param id 策略版本行 ID
     * @return 更新后的策略版本
     */
    @PostMapping("/{id}/disable")
    public R<StrategyVersionVO> disable(
            @PathVariable("id") Long id) {
        log.info("===> StrategyVersionController disable 停用策略版本, id={}", id);
        return R.ok(strategyVersionService.disable(id), "停用成功");
    }

    /**
     * 删除策略版本（有绑定引用时拒绝）。
     *
     * @param id 策略版本行 ID
     * @return 删除成功为 true
     */
    @DeleteMapping("/{id}")
    public R<Boolean> delete(
            @PathVariable("id") Long id) {
        log.info("===> StrategyVersionController delete 删除策略版本, id={}", id);
        return R.ok(strategyVersionService.delete(id), "删除成功");
    }
}
