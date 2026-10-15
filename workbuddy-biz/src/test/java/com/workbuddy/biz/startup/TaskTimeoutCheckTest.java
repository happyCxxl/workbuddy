package com.workbuddy.biz.startup;

import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.biz.config.TaskQueueProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单任务超时值校验单测：默认值通过并推进链；某个环节的超时非正即拒且不推进。
 *
 * @author cxxl
 */
class TaskTimeoutCheckTest {

    /** 链的替身：记录是否被推进 */
    private final AtomicBoolean proceeded = new AtomicBoolean();

    private final StartupCheckChain next = () -> proceeded.set(true);

    @Test
    void shouldPassWithDefaultTimeouts() {
        new TaskTimeoutCheck(new TaskQueueProperties()).check(next);

        assertTrue(proceeded.get(), "通过路径必须推进链");
    }

    @Test
    void shouldRejectZeroTimeout() {
        TaskQueueProperties properties = new TaskQueueProperties();
        properties.getTimeout().put(PipelineStage.CHUNK, Duration.ZERO);

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> new TaskTimeoutCheck(properties).check(next));

        assertTrue(error.getMessage().contains("CHUNK"), () -> "message=" + error.getMessage());
        assertFalse(proceeded.get(), "失败即止，不推进链");
    }

    @Test
    void shouldRejectNegativeTimeout() {
        TaskQueueProperties properties = new TaskQueueProperties();
        properties.getTimeout().put(PipelineStage.EMBED, Duration.ofMinutes(-1));

        assertThrows(KnowledgeException.class, () -> new TaskTimeoutCheck(properties).check(next));
    }
}
