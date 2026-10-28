package com.workbuddy.common.enums.embed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量模型目录单测。
 *
 * <p>守护 {@code of} 的模型名解析、默认启用模型与目录声明（维度/度量/归一化）：
 * 目录是保存校验与前端展示的单一事实源。
 */
class EmbeddingModelTest {

    /**
     * 每个模型都能按渠道名解析回来。
     *
     * <p>守护 {@code of}：模型名改错即失败（策略快照解析不了）。
     */
    @Test
    void ofShouldResolveEveryKey() {
        for (EmbeddingModel model : EmbeddingModel.values()) {
            assertEquals(model, EmbeddingModel.of(model.key()), () -> model + " 按渠道名解析失败");
        }
        assertNull(EmbeddingModel.of("no-such-model"));
    }

    /**
     * 默认启用模型是 text-embedding-v4（新建库的缺省选择）。
     *
     * <p>守护 {@code firstEnabled}：默认模型改错即失败（新库初始指向不可用模型）。
     */
    @Test
    void firstEnabledShouldBeV4() {
        assertEquals(EmbeddingModel.TEXT_EMBEDDING_V4, EmbeddingModel.firstEnabled());
    }

    /**
     * 备选与旧版模型在目录中保留但不启用。
     *
     * <p>守护 {@code enabled}：把备选模型标成启用即失败（保存校验放行不可用模型）。
     */
    @Test
    void optionalModelsShouldStayDisabled() {
        assertTrue(EmbeddingModel.TEXT_EMBEDDING_V4.enabled());
        assertFalse(EmbeddingModel.TEXT_EMBEDDING_V3.enabled());
        assertFalse(EmbeddingModel.TEXT_EMBEDDING_V2.enabled());
    }

    /**
     * 目录声明与文档一致：v2 为 1536 维旧版，v3/v4 为 1024 维，度量均为 COSINE 且需归一化。
     *
     * <p>守护 {@code dimension}/{@code metric}/{@code normalized}：声明改错即失败
     * （四关校验的口径随声明漂移）。
     */
    @Test
    void catalogDeclarationsShouldMatchDocumentedValues() {
        assertEquals(1024, EmbeddingModel.TEXT_EMBEDDING_V4.dimension());
        assertEquals(1024, EmbeddingModel.TEXT_EMBEDDING_V3.dimension());
        assertEquals(1536, EmbeddingModel.TEXT_EMBEDDING_V2.dimension());
        for (EmbeddingModel model : EmbeddingModel.values()) {
            assertEquals(EmbedMetric.COSINE, model.metric(), () -> model + " 度量应为 COSINE");
            assertTrue(model.normalized(), () -> model + " COSINE 度量必须归一化");
            assertTrue(model.contextWindowTokens() > 0, () -> model + " 上下文窗口应为正数");
        }
    }
}
