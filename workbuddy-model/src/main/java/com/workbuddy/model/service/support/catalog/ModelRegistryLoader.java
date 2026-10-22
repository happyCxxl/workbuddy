package com.workbuddy.model.service.support.catalog;

import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.domain.model.RegisteredModel;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.enums.model.ModelStatus;
import com.workbuddy.model.db.WbModelDbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型注册表装载器：读 wb_model 启用行 → 映射成注册表行 → 全量推入 {@link ModelRegistry}。
 *
 * <p>启动装载与刷新（Redis 广播、刷新端点）共用 {@link #reload()} 这一条路径；
 * 查库失败异常上抛：启动期终止启动，刷新期报错给操作方。
 * 行序沿用查询口径（厂商码、模型名升序）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRegistryLoader {

    private final WbModelDbService wbModelDbService;

    private final ModelRegistry modelRegistry;

    /**
     * 重读启用行并全量推入注册表。
     *
     * @return 装载行数
     */
    public int reload() {
        List<RegisteredModel> rows = wbModelDbService.listByCondition(ModelStatus.ENABLED.name()).stream()
                .map(this::toRegistered)
                .toList();
        modelRegistry.reload(rows);
        log.info("===> 模型注册表装载完成, 启用模型 {} 行", rows.size());
        return rows.size();
    }

    /** 启动即装载：失败上抛终止启动（空表合法，注册表为空）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reload();
    }

    /** wb_model 启用行 → 注册表行：能力 CSV 解析一次，长度/价格/限额/状态全量搬运 */
    private RegisteredModel toRegistered(WbModel row) {
        RegisteredModel model = new RegisteredModel();
        model.setVendorCode(row.getVendorCode());
        model.setModelKey(row.getModelKey());
        model.setCapabilities(ModelCapability.parse(row.getCapabilities()));
        model.setContextWindow(row.getContextWindow());
        model.setInputLimit(row.getInputLimit());
        model.setOutputLimit(row.getOutputLimit());
        model.setPriceInPerM(row.getPriceInPerM());
        model.setPriceInPerMCacheHit(row.getPriceInPerMCacheHit());
        model.setPriceOutPerM(row.getPriceOutPerM());
        model.setCurrency(row.getCurrency());
        model.setRpmLimit(row.getRpmLimit());
        model.setTpmLimit(row.getTpmLimit());
        model.setStatus(row.getStatus());
        return model;
    }
}
