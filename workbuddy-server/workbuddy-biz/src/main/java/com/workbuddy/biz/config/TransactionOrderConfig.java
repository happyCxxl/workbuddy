package com.workbuddy.biz.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 事务增强的显式顺序：固定排在其他切面外层。
 *
 * <p>审计切面在方法返回后才写审计，必须发生在事务提交之前 —— 这样审计写失败时业务能回滚，
 * 不会出现"业务已提交、审计缺失"。Spring 默认把事务增强排在
 * {@link Ordered#LOWEST_PRECEDENCE}，与未显式排序的切面同序、相对位置不确定，
 * 因此这里把事务增强提到外层（数值更小 = 更靠外）。
 *
 * <p>副作用的代价：其他切面（如限流）落进事务内层，被拒绝的请求会开一个空事务再回滚。
 */
@Configuration
@EnableTransactionManagement(proxyTargetClass = true, order = Ordered.LOWEST_PRECEDENCE - 100)
public class TransactionOrderConfig {
}
