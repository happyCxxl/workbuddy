package com.workbuddy.model.service.support.catalog;

import com.workbuddy.common.convert.model.ModelConverter;
import com.workbuddy.common.domain.model.RegisteredModel;
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

    private final ModelConverter modelConverter;

    /**
     * 重读启用行并全量推入注册表。
     *
     * @return 装载行数
     */
    public int reload() {
        List<RegisteredModel> rows = wbModelDbService.listByCondition(ModelStatus.ENABLED.name()).stream()
                .map(modelConverter::toRegistered)
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
}
