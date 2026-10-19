package com.workbuddy.common.enums.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片参数目录单测。
 *
 * <p>守护 {@code of} 的（算法，键）二维解析：参数目录是保存校验与默认值补齐的依据，
 * 键与算法错位即失败。
 */
class ChunkParamTest {

    /**
     * 每个参数都能按自己的算法与键解析回来，且说明非空。
     *
     * <p>守护 {@code of} 与 {@code desc}：任一参数的键改错或说明缺失即失败。
     */
    @Test
    void ofShouldResolveEveryAlgorithmAndKey() {
        for (ChunkParam param : ChunkParam.values()) {
            assertEquals(param, ChunkParam.of(param.algorithm(), param.key()),
                    () -> param + " 按自身算法与键解析失败");
            assertFalse(param.desc().isBlank(), () -> param + " 缺参数说明");
        }
    }

    /**
     * 算法不符时返回 null（正文路的参数不能挂到表格路）。
     *
     * <p>守护 {@code of} 的算法维度：丢掉算法匹配即失败（跨路参数串位）。
     */
    @Test
    void ofShouldReturnNullOnAlgorithmMismatch() {
        assertNotNull(ChunkParam.of(ChunkAlgorithm.BODY_FIXED_WINDOW, "len"));
        assertNull(ChunkParam.of(ChunkAlgorithm.TABLE_ROW_SLICE, "len"));
        assertNull(ChunkParam.of(ChunkAlgorithm.BODY_FIXED_WINDOW, "no-such-param"));
    }

    /**
     * 有界参数的取值范围下界不超过上界。
     *
     * <p>守护 {@code min}/{@code max} 的范围口径：界线写反即失败（校验与前端限制失真）。
     */
    @Test
    void boundedParamsShouldHaveOrderedRange() {
        for (ChunkParam param : ChunkParam.values()) {
            if (param.min() > 0 && param.max() > 0) {
                assertTrue(param.min() <= param.max(), () -> param + " 取值范围下界超过上界");
            }
        }
    }
}
