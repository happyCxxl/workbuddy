package com.workbuddy.biz.startup;

import com.workbuddy.biz.config.RetrievalCapabilityProperties;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.startup.StartupCheckChain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索能力名校验单测：合法名与空配置通过并推进链；非法名即拒、消息里带出非法名且不推进。
 */
class RetrievalCapabilityCheckTest {

    /** 链的替身：记录是否被推进 */
    private final AtomicBoolean proceeded = new AtomicBoolean();

    private final StartupCheckChain next = () -> proceeded.set(true);

    /**
     * 守护 {@code shouldPassWhenNothingEnabled}：该场景行为改动即失败。
     */
    @Test
    void shouldPassWhenNothingEnabled() {
        new RetrievalCapabilityCheck(properties()).check(next);

        assertTrue(proceeded.get(), "通过路径必须推进链");
    }

    /** 大小写与空白不敏感：小写与带空格的写法都算合法（{@code shouldPassWithLegalNames}）。 */
    @Test
    void shouldPassWithLegalNames() {
        new RetrievalCapabilityCheck(properties("WEIGHTED", " rerank ")).check(next);

        assertTrue(proceeded.get());
    }

    /**
     * 守护 {@code shouldRejectUnknownName}：该场景行为改动即失败。
     */
    @Test
    void shouldRejectUnknownName() {
        RetrievalCapabilityCheck check = new RetrievalCapabilityCheck(properties("EXPANDS"));

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> check.check(next));

        assertTrue(error.getMessage().contains("EXPANDS"), () -> "message=" + error.getMessage());
        assertFalse(proceeded.get(), "失败即止，不推进链");
    }

    /** 空白项忽略，不算非法名（{@code shouldIgnoreBlankEntries}）。 */
    @Test
    void shouldIgnoreBlankEntries() {
        new RetrievalCapabilityCheck(properties("  ")).check(next);

        assertTrue(proceeded.get());
    }

    private RetrievalCapabilityProperties properties(String... names) {
        RetrievalCapabilityProperties properties = new RetrievalCapabilityProperties();
        properties.setEnabled(List.of(names));
        return properties;
    }
}
