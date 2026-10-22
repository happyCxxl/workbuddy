package com.workbuddy.biz.startup;

import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.biz.config.ConfigFixtures;
import com.workbuddy.biz.config.TaskQueueProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单任务超时值校验单测：默认值通过并推进链；某个环节的超时非正即拒且不推进。
 */
class TaskTimeoutCheckTest {

    /** 链的替身：记录是否被推进 */
    private final AtomicBoolean proceeded = new AtomicBoolean();

    private final StartupCheckChain next = () -> proceeded.set(true);

    /**
     * 守护 {@code shouldPassWithDefaultTimeouts}：该场景行为改动即失败。
     */
    @Test
    void shouldPassWithDefaultTimeouts() {
        new TaskTimeoutCheck(ConfigFixtures.task()).check(next);

        assertTrue(proceeded.get(), "通过路径必须推进链");
    }

    /**
     * 守护 {@code shouldRejectZeroTimeout}：该场景行为改动即失败。
     */
    @Test
    void shouldRejectZeroTimeout() {
        TaskQueueProperties properties = ConfigFixtures.task();
        properties.getTimeout().put(PipelineStage.CHUNK, Duration.ZERO);

        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> new TaskTimeoutCheck(properties).check(next));

        assertTrue(error.getMessage().contains("CHUNK"), () -> "message=" + error.getMessage());
        assertFalse(proceeded.get(), "失败即止，不推进链");
    }

    /**
     * 守护 {@code shouldRejectNegativeTimeout}：该场景行为改动即失败。
     */
    @Test
    void shouldRejectNegativeTimeout() {
        TaskQueueProperties properties = ConfigFixtures.task();
        properties.getTimeout().put(PipelineStage.EMBED, Duration.ofMinutes(-1));

        assertThrows(WorkbuddyException.class, () -> new TaskTimeoutCheck(properties).check(next));
    }

    /**
     * 守护 {@code shouldPassWhenTimeoutSectionAbsent}：该场景行为改动即失败。
     */
    @Test
    void shouldPassWhenTimeoutSectionAbsent() {
        new TaskTimeoutCheck(new TaskQueueProperties()).check(next);

        assertTrue(proceeded.get(), "未配置任何环节超时时按全局默认放行");
    }
}
