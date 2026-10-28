package com.workbuddy.common.security.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 审计表达式求值单测：入参/返回值变量、数字字符串、空结果与坏表达式的判定口径。
 */
class AuditExpressionEvaluatorTest {

    private final AuditExpressionEvaluator evaluator = new AuditExpressionEvaluator();

    /**
     * 守护 {@code shouldResolveFromArgsAndResult}：入参位置与返回值都能取到 ID。
     */
    @Test
    void shouldResolveFromArgsAndResult() {
        assertEquals(7L, evaluator.evaluateId("#p0", new Object[] {7L}, null));
        assertEquals(9L, evaluator.evaluateId("#result", new Object[0], 9L));
        assertEquals(5L, evaluator.evaluateId("#p0.id", new Object[] {new IdHolder(5L)}, null));
        assertEquals(6L, evaluator.evaluateId("#p1.id", new Object[] {1L, new IdHolder(6L)}, null));
    }

    /** 带 id 属性的入参替身（模拟请求 DTO） */
    public static final class IdHolder {

        private final Long id;

        /**
         * 构造替身。
         *
         * @param id 对象 ID
         */
        public IdHolder(Long id) {
            this.id = id;
        }

        /**
         * 取对象 ID。
         *
         * @return 对象 ID
         */
        public Long getId() {
            return id;
        }
    }

    /**
     * 守护 {@code shouldAcceptNumericText}：数字字符串按整数解析。
     */
    @Test
    void shouldAcceptNumericText() {
        assertEquals(12L, evaluator.evaluateId("#result", new Object[0], "12"));
    }

    /**
     * 守护 {@code shouldRejectEmptyResult}：结果为空即失败，避免落 object_id = "null"。
     */
    @Test
    void shouldRejectEmptyResult() {
        assertThrows(IllegalStateException.class,
                () -> evaluator.evaluateId("#result", new Object[0], null));
        assertThrows(IllegalStateException.class,
                () -> evaluator.evaluateId("#result", new Object[0], "not-a-number"));
    }

    /**
     * 守护 {@code shouldRejectBlankOrBrokenExpression}：注解写错必须立刻暴露。
     */
    @Test
    void shouldRejectBlankOrBrokenExpression() {
        assertThrows(IllegalStateException.class, () -> evaluator.evaluateId(" ", new Object[0], 1L));
        assertThrows(IllegalStateException.class, () -> evaluator.evaluateId("#p9.id", new Object[0], 1L));
    }
}
