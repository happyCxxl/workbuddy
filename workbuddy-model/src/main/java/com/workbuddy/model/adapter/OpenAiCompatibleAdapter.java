package com.workbuddy.model.adapter;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.domain.model.chat.ChatOptions;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.domain.model.chat.TokenUsage;
import com.workbuddy.common.enums.model.FinishReason;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.engine.ModelInstanceRegistry;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容协议适配器：把我们的请求契约映射成 LC4j 调用，再把响应映射回我们的结果。
 *
 * <p>只做协议转换，不做选路、限流与降级；异常原样抛出，交由调用层分类。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleAdapter implements ModelAdapter {

    private final ModelInstanceRegistry instanceRegistry;

    /**
     * 对话调用：消息与采样参数透传给 LC4j。
     *
     * @param model   目录行
     * @param request 对话请求
     * @return 对话结果
     */
    @Override
    public ChatResult chat(ModelDescriptor model, ChatRequest request) {
        dev.langchain4j.model.chat.request.ChatRequest.Builder builder =
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(toLc4jMessages(request));
        ChatOptions options = request.getOptions();
        if (NullUtil.isNotNull(options)) {
            if (NullUtil.isNotNull(options.getTemperature())) {
                builder.temperature(options.getTemperature());
            }
            if (NullUtil.isNotNull(options.getMaxOutputTokens())) {
                builder.maxOutputTokens(options.getMaxOutputTokens());
            }
        }
        ChatResponse response = instanceRegistry.chat(model).chat(builder.build());
        return toChatResult(response, model);
    }

    /**
     * 向量编码：文本按请求顺序提交，向量按同一顺序回填。
     *
     * @param model   目录行
     * @param request 向量化请求
     * @return 向量结果
     */
    @Override
    public EmbeddingResult embed(ModelDescriptor model, EmbeddingRequest request) {
        List<TextSegment> segments = new ArrayList<>();
        if (NullUtil.isNotNull(request.getTexts())) {
            for (String text : request.getTexts()) {
                segments.add(TextSegment.from(StrUtil.blankToDefault(text, "")));
            }
        }
        Response<List<Embedding>> response = instanceRegistry.embedding(model).embedAll(segments);
        List<List<Float>> vectors = new ArrayList<>();
        if (NullUtil.isNotNull(response) && NullUtil.isNotNull(response.content())) {
            for (Embedding embedding : response.content()) {
                vectors.add(embedding.vectorAsList());
            }
        }
        EmbeddingResult result = new EmbeddingResult();
        result.setEmbeddings(vectors);
        result.setDimension(vectors.isEmpty() ? null : vectors.getFirst().size());
        result.setModel(request.getModel());
        result.setRequestId(request.getRequestId());
        return result;
    }

    /** 我们的消息 → LC4j 消息；角色为空按 user 处理 */
    private List<dev.langchain4j.data.message.ChatMessage> toLc4jMessages(ChatRequest request) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        if (NullUtil.isNull(request.getMessages())) {
            return messages;
        }
        for (com.workbuddy.common.domain.model.chat.ChatMessage message : request.getMessages()) {
            String content = StrUtil.blankToDefault(message.getContent(), "");
            com.workbuddy.common.domain.model.chat.ChatMessage.Role role = message.getRole();
            if (NullUtil.isNull(role)) {
                role = com.workbuddy.common.domain.model.chat.ChatMessage.Role.USER;
            }
            switch (role) {
                case SYSTEM -> messages.add(new SystemMessage(content));
                case ASSISTANT -> messages.add(new AiMessage(content));
                default -> messages.add(new UserMessage(content));
            }
        }
        return messages;
    }

    /** LC4j 响应 → 我们的对话结果 */
    private ChatResult toChatResult(ChatResponse response, ModelDescriptor model) {
        ChatResult result = new ChatResult();
        AiMessage aiMessage = response.aiMessage();
        result.setContent(NullUtil.isNull(aiMessage) ? null : aiMessage.text());
        result.setModel(StrUtil.blankToDefault(response.modelName(), model.getModelKey()));
        result.setId(response.id());
        result.setFinishReason(toFinishReason(response.finishReason()));
        result.setTokenUsage(toTokenUsage(response.tokenUsage()));
        return result;
    }

    /** LC4j 停止原因 → 我们的字典（工具执行归 OTHER） */
    private FinishReason toFinishReason(dev.langchain4j.model.output.FinishReason reason) {
        if (NullUtil.isNull(reason)) {
            return FinishReason.OTHER;
        }
        return switch (reason) {
            case STOP -> FinishReason.STOP;
            case LENGTH -> FinishReason.LENGTH;
            case CONTENT_FILTER -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.OTHER;
        };
    }

    /** LC4j 用量 → 我们的用量；缺失返回 null */
    private TokenUsage toTokenUsage(dev.langchain4j.model.output.TokenUsage usage) {
        if (NullUtil.isNull(usage)) {
            return null;
        }
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setInputTokens(usage.inputTokenCount());
        tokenUsage.setOutputTokens(usage.outputTokenCount());
        tokenUsage.setTotalTokens(usage.totalTokenCount());
        return tokenUsage;
    }
}
