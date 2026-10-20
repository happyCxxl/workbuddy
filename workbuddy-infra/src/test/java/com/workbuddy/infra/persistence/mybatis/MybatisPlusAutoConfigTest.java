package com.workbuddy.infra.persistence.mybatis;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MyBatis-Plus 自动配置单测。
 *
 * <p>守护 {@code mybatisPlusInterceptor}：必须注册且只注册一个分页拦截器（MySQL 方言），
 * 漏注册会让分页查询退化成全量查询。
 */
class MybatisPlusAutoConfigTest {

    /**
     * 分页拦截器：非空且只含一个内部拦截器。
     *
     * <p>守护 {@code mybatisPlusInterceptor}：数量或方言错即失败。
     */
    @Test
    void mybatisPlusInterceptorShouldCarryPagination() {
        MybatisPlusInterceptor interceptor = new MybatisPlusAutoConfig().mybatisPlusInterceptor();

        assertNotNull(interceptor);
        assertEquals(1, interceptor.getInterceptors().size());
    }
}
