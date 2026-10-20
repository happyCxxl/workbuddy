package com.workbuddy.biz.service.support;

import com.workbuddy.biz.config.RetrievalCapabilityProperties;
import com.workbuddy.worker.retrieval.RetrievalCapability;
import com.workbuddy.biz.service.support.retrieval.RetrievalCapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索能力开关单测。
 *
 * <p>守护 {@code isEnabled} 的集合判定：恒 true 会把未启用的能力放给用户，恒 false 会让已启用能力全不可见。
 */
class RetrievalCapabilityRegistryTest {

    /**
     * 只放行配置里列出的能力。
     *
     * <p>守护 {@code isEnabled}：判定写死即失败。
     */
    @Test
    void isEnabledShouldFollowConfiguredNames() {
        RetrievalCapabilityProperties props = mock(RetrievalCapabilityProperties.class);
        RetrievalCapability enabled = RetrievalCapability.WEIGHTED;
        when(props.enabledNames()).thenReturn(Set.of(enabled.name()));

        RetrievalCapabilityRegistry registry = new RetrievalCapabilityRegistry(props);

        assertTrue(registry.isEnabled(enabled));
        assertFalse(registry.isEnabled(RetrievalCapability.RERANK));
    }
}
