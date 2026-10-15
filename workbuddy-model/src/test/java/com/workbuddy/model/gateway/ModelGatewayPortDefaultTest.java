package com.workbuddy.model.gateway;

import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 端口默认实现单测：chat / vision 未实现即抛 KnowledgeException，错误码取模型调用分区，detail 带模态名与厂商码。
 *
 * @author cxxl
 */
class ModelGatewayPortDefaultTest {

    /** 只实现 embed 的端口；chat / vision 走默认实现 */
    private final ModelGatewayPort port = request -> new EmbeddingResult();

    @Test
    void chatShouldThrowUnsupportedModalityWithVendorAndModality() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> port.chat("dashscope", new ChatRequest()));

        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED, error.getErrorCode());
        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED.getCode(), error.getCode());
        assertEquals("模态：chat，厂商：dashscope", error.getMessage());
    }

    @Test
    void visionShouldThrowUnsupportedModalityWithVendorAndModality() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> port.vision("dashscope", new VisionRequest()));

        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED, error.getErrorCode());
        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED.getCode(), error.getCode());
        assertEquals("模态：vision，厂商：dashscope", error.getMessage());
    }

    @Test
    void visionByCapabilityShouldThrowUnsupportedModalityWithCapability() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> port.visionByCapability(ModelCapability.OCR, new VisionRequest()));

        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED, error.getErrorCode());
        assertEquals("模态：vision，能力：OCR", error.getMessage());
    }

    @Test
    void resolveVendorCodeShouldThrowUnsupportedModalityWithModel() {
        KnowledgeException error = assertThrows(KnowledgeException.class, () -> port.resolveVendorCode("qwen-max"));

        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED, error.getErrorCode());
        assertEquals("厂商解析，模型：qwen-max", error.getMessage());
    }

    @Test
    void embedShouldStayOnItsOwnContract() {
        EmbeddingResult result = port.embed(null);

        assertNull(result.getEmbeddings());
        assertNull(result.getDimension());
        assertNull(result.getModel());
        assertNull(result.getRequestId());
    }
}
