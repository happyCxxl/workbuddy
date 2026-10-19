package com.workbuddy.common.enums.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型能力目录单测。
 *
 * <p>守护 {@code of} 的码值解析与 {@code parse}/{@code join} 的逗号串往返：
 * 能力目录是模型保存校验与前端展示的依据。
 */
class ModelCapabilityTest {

    /**
     * 每种能力都能按码值解析回来。
     *
     * <p>守护 {@code of}：码值改错即失败（保存的能力集解析不了）。
     */
    @Test
    void ofShouldResolveEveryCode() {
        for (ModelCapability capability : ModelCapability.values()) {
            assertEquals(capability, ModelCapability.of(capability.name()),
                    () -> capability + " 按码值解析失败");
        }
        assertNull(ModelCapability.of("NO_SUCH"));
    }

    /**
     * 空白输入解析为空集合，不报错。
     *
     * <p>守护 {@code parse} 的空白口径：把空白当未知码值即失败（空能力集被误判）。
     */
    @Test
    void parseShouldReturnEmptySetForBlankText() {
        assertTrue(ModelCapability.parse("").isEmpty());
        assertTrue(ModelCapability.parse(null).isEmpty());
    }

    /**
     * 逗号串按声明的顺序解析为能力集合。
     *
     * <p>守护 {@code parse}：拆分或去重错位即失败。
     */
    @Test
    void parseShouldSplitCommaSeparatedCodes() {
        Set<ModelCapability> parsed = ModelCapability.parse("CHAT,EMBEDDING");
        assertEquals(Set.of(ModelCapability.CHAT, ModelCapability.EMBEDDING), parsed);
    }

    /**
     * join 与 parse 互为往返：组合串再拼回保持一致。
     *
     * <p>守护 {@code join}：空集合回空串，非空按声明序拼接。
     */
    @Test
    void joinShouldRoundTripWithParse() {
        assertEquals("", ModelCapability.join(null));
        assertEquals("", ModelCapability.join(Set.of()));
        String joined = ModelCapability.join(Set.of(ModelCapability.CHAT, ModelCapability.EMBEDDING));
        assertEquals(Set.of(ModelCapability.CHAT, ModelCapability.EMBEDDING), ModelCapability.parse(joined));
    }
}
