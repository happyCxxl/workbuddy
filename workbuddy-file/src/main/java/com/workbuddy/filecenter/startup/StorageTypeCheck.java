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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageTypeCheck implements StartupCheck {

    private final FileCenterConfig fileCenterConfig;

    /**
     * 校验序号：参数类校验。
     *
     * @return 校验顺序
     */
    @Override
    public int order() {
        return ORDER_PARAMETERS;
    }

    /**
     * 校验存储类型配置可解析，通过后把控制权交给链上的下一项。
     *
     * @param next 启动校验链的下一项
     */
    @Override
    public void check(StartupCheckChain next) {
        fileCenterConfig.defaultType();
        log.info("存储类型校验通过, file-center.storage-type={}", fileCenterConfig.getStorageType());
        next.proceed();
    }
}
