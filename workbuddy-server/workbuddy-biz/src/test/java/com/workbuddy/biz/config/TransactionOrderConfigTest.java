package com.workbuddy.biz.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务增强顺序配置单测：事务增强必须排在业务切面外层，审计切面才能落在事务内层。
 */
class TransactionOrderConfigTest {

    /**
     * 守护 {@code shouldPutTransactionAdviceOutsideOtherAspects}：顺序改回默认值即失败。
     */
    @Test
    void shouldPutTransactionAdviceOutsideOtherAspects() {
        EnableTransactionManagement config =
                TransactionOrderConfig.class.getAnnotation(EnableTransactionManagement.class);

        assertNotNull(config, "配置类必须声明 @EnableTransactionManagement");
        assertEquals(Ordered.LOWEST_PRECEDENCE - 100, config.order());
        assertTrue(config.proxyTargetClass(), "与 Spring Boot 默认的 CGLIB 代理保持一致");
    }
}
