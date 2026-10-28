package com.workbuddy.biz.testkit;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 测试用事务模板：回调直接执行，commit / rollback 不落真实资源。
 *
 * <p>用于 ServiceImpl 的纯单测；生产使用 Spring Boot 自动配置的 {@link TransactionTemplate}。
 */
public final class TransactionTestSupport {

    private TransactionTestSupport() {
    }

    /**
     * 构造一个无真实资源的 {@link TransactionTemplate}。
     *
     * @return 事务模板
     */
    public static TransactionTemplate template() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // 单测不落真实事务
            }

            @Override
            public void rollback(TransactionStatus status) {
                // 单测不落真实事务
            }
        });
    }
}
