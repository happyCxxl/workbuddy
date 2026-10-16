package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDto;
import com.workbuddy.common.dto.request.strategy.StrategyVersionUpdateDto;
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
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/strategy-versions")
public class KnowledgeStrategyController {

    private final StrategyVersionService strategyVersionService;

    @GetMapping
    public R<List<StrategyVersionVO>> list(
            @RequestParam("type") String type,
            @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return R.ok(strategyVersionService.list(type, includeInactive));
    }

    @PostMapping
    public R<StrategyVersionVO> create(@Valid @RequestBody StrategyVersionCreateDto dto) {
        log.info("===> StrategyVersionController create 创建策略版本, type={}, name={}, version={}",
                dto.getType(), dto.getName(), dto.getVersion());
        return R.ok(strategyVersionService.create(dto), "创建成功");
    }

    @PutMapping("/{id}")
    public R<StrategyVersionVO> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody StrategyVersionUpdateDto dto) {
        log.info("===> StrategyVersionController update 编辑策略版本, id={}", id);
        return R.ok(strategyVersionService.update(id, dto), "更新成功");
    }

    @PostMapping("/{id}/enable")
    public R<StrategyVersionVO> enable(
            @PathVariable("id") Long id) {
        log.info("===> StrategyVersionController enable 启用策略版本, id={}", id);
        return R.ok(strategyVersionService.enable(id), "启用成功");
    }

    @PostMapping("/{id}/disable")
    public R<StrategyVersionVO> disable(
            @PathVariable("id") Long id) {
        log.info("===> StrategyVersionController disable 停用策略版本, id={}", id);
        return R.ok(strategyVersionService.disable(id), "停用成功");
    }

    @DeleteMapping("/{id}")
    public R<Boolean> delete(
            @PathVariable("id") Long id) {
        log.info("===> StrategyVersionController delete 删除策略版本, id={}", id);
        return R.ok(strategyVersionService.delete(id), "删除成功");
    }
}
