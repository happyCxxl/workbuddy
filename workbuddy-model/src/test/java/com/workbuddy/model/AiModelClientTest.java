package com.workbuddy.model;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.domain.model.vision.VisionRequest;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.model.service.support.invoke.ModelInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门面单测：只做转发与空请求拒绝，选路细节在调用器。
 */
@ExtendWith(MockitoExtension.class)
class AiModelClientTest {

    @Mock
    private ModelInvoker modelInvoker;

    /** chat：转发给调用器（{@code chatShouldDelegate}）。 */
    @Test
    void chatShouldDelegate() {
        AiModelClient client = new AiModelClient(modelInvoker);
        ChatRequest request = new ChatRequest();
        ChatResult expected = new ChatResult();
        when(modelInvoker.chat(request)).thenReturn(expected);

        ChatResult actual = client.chat(request);

        assertSame(expected, actual);
        verify(modelInvoker).chat(request);
    }

    /** embed：转发给调用器（{@code embedShouldDelegate}）。 */
    @Test
    void embedShouldDelegate() {
        AiModelClient client = new AiModelClient(modelInvoker);
        EmbeddingRequest request = new EmbeddingRequest();
        EmbeddingResult expected = new EmbeddingResult();
        when(modelInvoker.embed(request)).thenReturn(expected);

        EmbeddingResult actual = client.embed(request);

        assertSame(expected, actual);
        verify(modelInvoker).embed(request);
    }

    /** 空请求：按 40001 拒绝（{@code shouldRejectNullRequests}）。 */
    @Test
    void shouldRejectNullRequests() {
        AiModelClient client = new AiModelClient(modelInvoker);

        assertEquals(ErrorCode.MODEL_BAD_REQUEST,
                assertThrows(WorkbuddyException.class, () -> client.chat(null)).getErrorCode());
        assertEquals(ErrorCode.MODEL_BAD_REQUEST,
                assertThrows(WorkbuddyException.class, () -> client.embed(null)).getErrorCode());
        assertEquals(ErrorCode.MODEL_BAD_REQUEST,
                assertThrows(WorkbuddyException.class, () -> client.vision(null)).getErrorCode());
    }

    /** 视觉：转发给调用器（{@code visionShouldDelegate}）。 */
    @Test
    void visionShouldDelegate() {
        AiModelClient client = new AiModelClient(modelInvoker);
        VisionRequest request = new VisionRequest();
        ChatResult expected = new ChatResult();
        when(modelInvoker.vision(request)).thenReturn(expected);

        ChatResult actual = client.vision(request);

        assertSame(expected, actual);
        verify(modelInvoker).vision(request);
    }
}
