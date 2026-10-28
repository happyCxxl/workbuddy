package com.workbuddy.common.enums.embed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 向量度量单测。
 *
 * <p>守护 {@code of} 的键解析与说明：度量目录是四关校验（归一化约束）的依据。
 */
class EmbedMetricTest {

    /**
     * 每种度量都能按键解析回来，且说明非空。
     *
     * <p>守护 {@code of} 与 {@code desc}：键改错或说明缺失即失败。
     */
    @Test
    void ofShouldResolveEveryKey() {
        for (EmbedMetric metric : EmbedMetric.values()) {
            assertEquals(metric, EmbedMetric.of(metric.key()), () -> metric + " 按键解析失败");
            assertFalse(metric.desc().isBlank(), () -> metric + " 缺度量说明");
        }
        assertNull(EmbedMetric.of("no-such-metric"));
    }
}
