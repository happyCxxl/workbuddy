package com.workbuddy.biz.config;

import com.workbuddy.common.enums.task.PipelineStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 任务队列配置单测：按环节取超时（枚举名与配置键两种写法都归一化命中、认不出回退默认），
 * 以及环节超时的绑定期口径（键是 PipelineStage 枚举，写错名直接拒）。
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
        properties.getTimeout().put(PipelineStage.CHUNK, Duration.ofMinutes(2));

        assertAll(
                () -> assertEquals(Duration.ofMinutes(2), properties.timeoutOf("chunk")),
                () -> assertEquals(Duration.ofMinutes(2), properties.timeoutOf("CHUNK")));
    }

    @Test
    void timeoutOfShouldUseCustomOverrideForNormalizedKey() {
        // 索引构建环节的配置键是 build-index、枚举名是 BUILD_INDEX：归一化后互相命中
        properties.getTimeout().put(PipelineStage.BUILD_INDEX, Duration.ofMinutes(3));

        assertAll(
                () -> assertEquals(Duration.ofMinutes(3), properties.timeoutOf("build-index")),
                () -> assertEquals(Duration.ofMinutes(3), properties.timeoutOf("BUILD_INDEX")));
    }

    /** 配置键写 kebab 形式（build-index）：宽松绑定认它，绑到 BUILD_INDEX */
    @Test
    void bindingShouldAcceptKebabStageKey() {
        TaskQueueProperties bound = new Binder(new MapConfigurationPropertySource(
                Map.of("knowledge.task.timeout.build-index", "3m")))
                .bind("knowledge.task", TaskQueueProperties.class)
                .orElseGet(TaskQueueProperties::new);

        assertEquals(Duration.ofMinutes(3), bound.timeoutOf("BUILD_INDEX"));
    }

    /** 环节名写错（pars）：绑定期直接拒，不再静默回退默认值 */
    @Test
    void bindingShouldRejectUnknownStageKey() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("knowledge.task.timeout.pars", "5m"));

        assertThrows(BindException.class, () -> new Binder(source).bind("knowledge.task", TaskQueueProperties.class));
    }
}
