package com.workbuddy.model.adapter;

import com.workbuddy.common.domain.model.AuthType;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 适配器 SPI 单测：鉴权支持集 + 两个模态默认方法的未支持口径（错误码、模态名、厂商码）。
 *
 * @author cxxl
 */
class ModelAdapterDefaultTest {

    /** 只实现对话模态的适配器：embed 与 vision 走 SPI 默认实现 */
    private final ModelAdapter adapter = new ModelAdapter() {

        @Override
        public Set<AuthType> supportedAuthTypes() {
            return Set.of(AuthType.BEARER);
        }

        @Override
        public GenerationResult chat(String vendorCode, ChatRequest request) {
            return new GenerationResult();
        }
    };

    @Test
    void supportedAuthTypesShouldCoverBearer() {
        assertEquals(Set.of(AuthType.BEARER), adapter.supportedAuthTypes());
    }

    @Test
    void embedShouldThrowUnsupportedModalityWithVendorAndModality() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> adapter.embed("dashscope", new EmbeddingRequest()));

        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED, error.getErrorCode());
        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED.getCode(), error.getCode());
        assertEquals("模态：embed，厂商：dashscope", error.getMessage());
    }

    @Test
    void visionShouldThrowUnsupportedModalityWithVendorAndModality() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> adapter.vision("custom", new VisionRequest()));

        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED, error.getErrorCode());
        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED.getCode(), error.getCode());
        assertEquals("模态：vision，厂商：custom", error.getMessage());
    }
}
