package com.workbuddy.biz.service.support;

import com.workbuddy.biz.config.RetrievalRunProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 检索运行记录开关：测试台检索必记；生产检索按 {@link RetrievalRunProperties} 决定（默认关，逐查询记录量大）。
 */
@Component
@RequiredArgsConstructor
public class RetrievalRunSettings {

    private final RetrievalRunProperties properties;

    /** 生产检索是否落运行记录 */
    public boolean recordProduction() {
        return properties.isRecordProduction();
    }
}
