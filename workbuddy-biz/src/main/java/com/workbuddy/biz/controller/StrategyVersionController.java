package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDto;
import com.workbuddy.common.dto.request.strategy.StrategyVersionUpdateDto;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "策略版本管理", description = "策略版本列表与增删改/启停")
public class StrategyVersionController {

    private final StrategyVersionService strategyVersionService;

    @GetMapping
    @Operation(summary = "策略版本列表", description = "按类型取策略版本（新→旧）；includeInactive=false 只启用中（触发前下拉），true 全部（管理页）；type=CHUNK")
    public R<List<StrategyVersionVO>> list(
            @Parameter(description = "策略类型（CHUNK）", required = true) @RequestParam("type") String type,
            @Parameter(description = "是否含停用版本（默认 false）") @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return R.ok(strategyVersionService.list(type, includeInactive));
    }

    @PostMapping
    @Operation(summary = "创建策略版本", description = "显式创建新版本行（默认启用；同环节同名版本撞唯一键报错）")
    public R<StrategyVersionVO> create(@Valid @RequestBody StrategyVersionCreateDto dto) {
        log.info("===> StrategyVersionController create 创建策略版本, type={}, name={}, version={}",
                dto.getType(), dto.getName(), dto.getVersion());
        return R.ok(strategyVersionService.create(dto), "创建成功");
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑策略版本", description = "编辑 = 复制新版本行（原行不可变；新行撞唯一键报 40001）")
    public R<StrategyVersionVO> update(
            @Parameter(description = "策略版本ID", required = true) @PathVariable("id") Long id,
            @Valid @RequestBody StrategyVersionUpdateDto dto) {
        log.info("===> StrategyVersionController update 编辑策略版本, id={}", id);
        return R.ok(strategyVersionService.update(id, dto), "更新成功");
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "启用策略版本", description = "启用（幂等）")
    public R<StrategyVersionVO> enable(
            @Parameter(description = "策略版本ID", required = true) @PathVariable("id") Long id) {
        log.info("===> StrategyVersionController enable 启用策略版本, id={}", id);
        return R.ok(strategyVersionService.enable(id), "启用成功");
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "停用策略版本", description = "停用（幂等；停用后不再作为显式指定或知识库绑定的可用策略版本，触发报 40433/40438）")
    public R<StrategyVersionVO> disable(
            @Parameter(description = "策略版本ID", required = true) @PathVariable("id") Long id) {
        log.info("===> StrategyVersionController disable 停用策略版本, id={}", id);
        return R.ok(strategyVersionService.disable(id), "停用成功");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除策略版本", description = "被知识库绑定引用时拒绝删除（40452，停用代替删除）；仅无引用可物理删除")
    public R<Boolean> delete(
            @Parameter(description = "策略版本ID", required = true) @PathVariable("id") Long id) {
        log.info("===> StrategyVersionController delete 删除策略版本, id={}", id);
        return R.ok(strategyVersionService.delete(id), "删除成功");
    }
}
