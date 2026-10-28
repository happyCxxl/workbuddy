package com.workbuddy.common.enums.preprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 预处理参数目录单测。
 *
 * <p>守护 {@code of} 的（规则，键）二维解析：参数目录是注册校验与默认值补齐的依据。
 */
class PreprocessParamTest {

    /**
     * 每个参数都能按自己的规则与键解析回来。
     *
     * <p>守护 {@code of}：任一参数的键或规则改错即失败。
     */
    @Test
    void ofShouldResolveEveryRuleAndKey() {
        for (PreprocessParam param : PreprocessParam.values()) {
            assertEquals(param, PreprocessParam.of(param.rule(), param.key()),
                    () -> param + " 按自身规则与键解析失败");
        }
    }

    /**
     * 规则不符或键未知时返回 null。
     *
     * <p>守护 {@code of} 的未命中口径：目录规则的参数挂到别的规则下即失败。
     */
    @Test
    void ofShouldReturnNullOnRuleMismatchOrUnknownKey() {
        assertEquals(PreprocessParam.TOC_MIN_LINES_PER_PAGE,
                PreprocessParam.of(PreprocessRule.TOC, PreprocessParam.TOC_MIN_LINES_PER_PAGE.key()));
        assertNull(PreprocessParam.of(PreprocessRule.FIELD, PreprocessParam.TOC_MIN_LINES_PER_PAGE.key()));
        assertNull(PreprocessParam.of(PreprocessRule.TOC, "no-such-param"));
    }

    /**
     * 有界参数的取值范围下界不超过上界。
     *
     * <p>守护 {@code min}/{@code max} 的范围口径：界线写反即失败。
     */
    @Test
    void boundedParamsShouldHaveOrderedRange() {
        for (PreprocessParam param : PreprocessParam.values()) {
            if (param.min() != null && param.max() != null) {
                assertFalse(param.min() > param.max(), () -> param + " 取值范围下界超过上界");
            }
        }
    }
}
