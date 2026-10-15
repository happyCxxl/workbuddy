package com.workbuddy.biz.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 任务队列配置单测（按环节取超时：枚举名归一化命中 + 未配置回退默认）。
 *
 * @author cxxl
 */
class TaskQueuePropertiesTest {

    private TaskQueueProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TaskQueueProperties();
    }

    @Test
    void timeoutOfShouldHitStageKeyPassedAsEnumName() {
        assertEquals(Duration.ofMinutes(5), properties.timeoutOf("PARSE"));
        assertEquals(Duration.ofMinutes(5), properties.timeoutOf("STRUCTURE"));
        assertEquals(Duration.ofMinutes(5), properties.timeoutOf("PREPROCESS"));
        assertEquals(Duration.ofMinutes(5), properties.timeoutOf("CHUNK"));
        assertEquals(Duration.ofMinutes(10), properties.timeoutOf("EMBED"));
        assertEquals(Duration.ofMinutes(10), properties.timeoutOf("BUILD_INDEX"));
    }

    @Test
    void timeoutOfShouldHitStageKeyPassedAsConfigKey() {
        assertEquals(Duration.ofMinutes(5), properties.timeoutOf("parse"));
        assertEquals(Duration.ofMinutes(10), properties.timeoutOf("embed"));
        assertEquals(Duration.ofMinutes(10), properties.timeoutOf("build-index"));
    }

    @Test
    void timeoutOfShouldFallbackForUnknownOrNullStage() {
        assertEquals(Duration.ofMinutes(5), properties.timeoutOf("UNKNOWN_STAGE"));
        assertEquals(Duration.ofMinutes(5), properties.timeoutOf(null));
    }

    @Test
    void timeoutOfShouldUseCustomStageOverride() {
        properties.getTimeout().put("chunk", Duration.ofMinutes(2));

        // 原样键与小写枚举名都命中；归一化键（下划线转连字符）在下面一条用例里覆盖
        assertAll(
                () -> assertEquals(Duration.ofMinutes(2), properties.timeoutOf("chunk")),
                () -> assertEquals(Duration.ofMinutes(2), properties.timeoutOf("CHUNK")));
    }

    @Test
    void timeoutOfShouldUseCustomOverrideForNormalizedKey() {
        // 索引构建环节的配置键是 build-index、枚举名是 BUILD_INDEX：归一化后互相命中
        properties.getTimeout().put("build-index", Duration.ofMinutes(3));

        assertAll(
                () -> assertEquals(Duration.ofMinutes(3), properties.timeoutOf("build-index")),
                () -> assertEquals(Duration.ofMinutes(3), properties.timeoutOf("BUILD_INDEX")));
    }
}
