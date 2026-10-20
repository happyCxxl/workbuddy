package com.workbuddy.biz.service.support;

import com.workbuddy.biz.config.RetrievalRunProperties;
import com.workbuddy.biz.service.support.retrieval.RetrievalRunSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索运行记录开关单测。
 *
 * <p>守护 {@code recordProduction} 的委托：恒 true 会让生产检索每查必落库，恒 false 会让压测/回放失去留痕。
 */
class RetrievalRunSettingsTest {

    /**
     * 开关原样取自配置。
     *
     * <p>守护 {@code recordProduction}：不读配置或写死都会让开关失效。
     */
    @Test
    void recordProductionShouldFollowProperties() {
        RetrievalRunProperties props = mock(RetrievalRunProperties.class);
        RetrievalRunSettings settings = new RetrievalRunSettings(props);

        when(props.isRecordProduction()).thenReturn(true);
        assertTrue(settings.recordProduction());

        when(props.isRecordProduction()).thenReturn(false);
        assertFalse(settings.recordProduction());
    }
}
