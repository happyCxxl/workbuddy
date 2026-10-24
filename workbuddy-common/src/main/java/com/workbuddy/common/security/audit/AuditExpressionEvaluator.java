package com.workbuddy.common.security.audit;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审计表达式求值：把 {@code @Audit(id = "...")} 的 SpEL 解成对象 ID。
 *
 * <p>变量只有方法入参（{@code #p0}、{@code #p1}…）与返回值（{@code #result}），
 * 不注册任何 bean 访问入口 —— 保证审计不反向依赖业务。
 */
@Component
public class AuditExpressionEvaluator {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    /** 表达式解析结果缓存：注解里的表达式是常量，解析一次即可 */
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * 求对象 ID：结果必须是非空整数。
     *
     * @param expression 注解上的 SpEL 表达式
     * @param args       方法入参
     * @param result     方法返回值
     * @return 对象 ID
     * @throws IllegalStateException 表达式为空、求值失败或结果不是整数
     */
    public Long evaluateId(String expression, Object[] args, Object result) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalStateException("审计注解缺少 id 表达式");
        }
        Object value;
        try {
            value = expressionCache.computeIfAbsent(expression, PARSER::parseExpression)
                    .getValue(contextOf(args, result));
        } catch (RuntimeException e) {
            throw new IllegalStateException("审计 id 表达式求值失败: " + expression, e);
        }
        Long objectId = toLong(value);
        if (objectId == null) {
            throw new IllegalStateException("审计 id 表达式结果为空: " + expression);
        }
        return objectId;
    }

    /** 求值上下文：只暴露入参与返回值 */
    private StandardEvaluationContext contextOf(Object[] args, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
        }
        context.setVariable("result", result);
        return context;
    }

    /** 表达式结果转 Long：整数与数字字符串都接受，其余视为配置错误 */
    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
