package com.workbuddy.model.gateway;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.adapter.ModelAdapter;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.invoke.pipeline.CallContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * 门面单测：chat/embed 的上下文装配（能力、模型名过滤）与空请求拒绝。
 */
@ExtendWith(MockitoExtension.class)
class AiModelClientTest {

    @Mock
    private ModelAdapter modelAdapter;

    @Mock
    private ModelInvoker modelInvoker;

    private AiModelClient client;

    private AiModelClient client() {
        return new AiModelClient(modelAdapter, modelInvoker);
    }

    private ModelDescriptor model(String modelKey) {
        ModelDescriptor model = new ModelDescriptor();
        model.setVendorCode("dashscope");
        model.setModelKey(modelKey);
        return model;
    }

    /** chat：能力取请求值，模型名过滤为空（{@code chatShouldBindCapability}）。 */
    @Test
    void chatShouldBindCapability() {
        client = client();
        ChatResult result = new ChatResult();
        doReturn(result).when(modelInvoker).invoke(any(CallContext.class));
        ChatRequest request = new ChatRequest();
        request.setCapability(ModelCapability.OCR);

        ChatResult actual = client.chat(request);

        assertSame(result, actual);
        ArgumentCaptor<CallContext> captor = ArgumentCaptor.forClass(CallContext.class);
        verify(modelInvoker).invoke(captor.capture());
        assertEquals(ModelCapability.OCR, captor.getValue().getCapability());
        assertNull(captor.getValue().getModelKeyFilter());
    }

    /** embed：能力固定 EMBEDDING，模型名作为精确过滤（{@code embedShouldFilterByModelKey}）。 */
    @Test
    void embedShouldFilterByModelKey() {
        client = client();
        EmbeddingResult result = new EmbeddingResult();
        doReturn(result).when(modelInvoker).invoke(any(CallContext.class));
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel("text-embedding-v4");
        request.setTexts(List.of("a"));

        EmbeddingResult actual = client.embed(request);

        assertSame(result, actual);
        ArgumentCaptor<CallContext> captor = ArgumentCaptor.forClass(CallContext.class);
        verify(modelInvoker).invoke(captor.capture());
        assertEquals(ModelCapability.EMBEDDING, captor.getValue().getCapability());
        assertEquals("text-embedding-v4", captor.getValue().getModelKeyFilter());

        ModelDescriptor descriptor = model("text-embedding-v4");
        captor.getValue().getCall().invoke(descriptor);
        verify(modelAdapter).embed(descriptor, request);
    }

    /** 空请求：按 40001 拒绝（{@code shouldRejectNullRequests}）。 */
    @Test
    void shouldRejectNullRequests() {
        client = client();

        assertEquals(ErrorCode.MODEL_BAD_REQUEST,
                assertThrows(KnowledgeException.class, () -> client.chat(null)).getErrorCode());
        assertEquals(ErrorCode.MODEL_BAD_REQUEST,
                assertThrows(KnowledgeException.class, () -> client.embed(null)).getErrorCode());
    }
}
