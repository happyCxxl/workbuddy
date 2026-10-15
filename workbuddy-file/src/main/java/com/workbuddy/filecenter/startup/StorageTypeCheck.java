package com.workbuddy.filecenter.startup;

import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.filecenter.config.FileCenterConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 存储类型启动期校验：`file-center.storage-type` 必须是合法存储类型码。
 *
 * <p>该值只是数据源播种与回落的兜底来源，数据源表里已有可用行时不会被读到；取值非法会一直潜伏到
 * 某次重建数据源表才在一次无关的启动里暴露。本项在启动期先验一遍，取值判定复用
 * {@link FileCenterConfig#defaultType()}。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageTypeCheck implements StartupCheck {

    private final FileCenterConfig fileCenterConfig;

    @Override
    public int order() {
        return ORDER_PARAMETERS;
    }

    @Override
    public void check(StartupCheckChain next) {
        fileCenterConfig.defaultType();
        log.info("存储类型校验通过, file-center.storage-type={}", fileCenterConfig.getStorageType());
        next.proceed();
    }
}
