package com.workbuddy.model.adapter;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.AuthType;
import com.workbuddy.common.domain.model.ChatMessage;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.MessageRole;
import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.engine.ModelInstanceRegistry;
import com.workbuddy.model.listener.ModelCallTrace;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.listener.ModelUsageMapper;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * OpenAI 兼容适配器：服务所有走该协议的厂商，完成「我们的契约 → LC4j 请求」与「LC4j 响应 → 出参」
 * 双向映射。LC4j 类型出现在 knowledge-model 内部的 `adapter` / `engine` / `invoke` / `listener` 四个包，
 * 业务侧不出现。三个模态（对话 / 向量 / 视觉）都走本类，出参统一为原始文本 + 通用树 + 用量 + 模型标识。
 * 调用上下文经请求选项的属性表交给用量监听器。
 *
 * @author cxxl
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleAdapter implements ModelAdapter {

    /** JSON_SCHEMA 直传时的 schema 名（OpenAI 兼容端点要求字母、数字、下划线、中划线） */
    private static final String JSON_SCHEMA_NAME = "output";

    private final ModelInstanceRegistry instanceRegistry;

    private final ModelCallTraceHolder modelCallTraceHolder;

    @Override
    public Set<AuthType> supportedAuthTypes() {
        return Set.of(AuthType.BEARER);
    }

    /**
     * 对话模态：按厂商码与请求里的模型名取实例 → 组装 LC4j 请求 → 调用 → 映射出参。
     *
     * @param vendorCode 厂商码
     * @param request    对话入参（模型/消息/输出约束/超时）
     * @return 原始文本 + 通用树 + 用量 + 模型标识
     */
    @Override
    public GenerationResult chat(String vendorCode, ChatRequest request) {
        ChatModel chatModel = instanceRegistry.chatModel(vendorCode, request.getModelKey());
        ChatResponse response = chatModel.chat(toLc4jRequest(request), requestOptions());
        return toGenerationResult(response, request.getModelKey());
    }

    /**
     * 视觉模态：按厂商码与请求里的模型名取实例 → 组装「文本 + 图像」多模态请求 → 调用 → 映射出参。
     * 复用对话模态那条实例获取、请求选项与响应映射路径，出参口径与 chat 一致。
     *
     * @param vendorCode 厂商码
     * @param request    视觉入参（模型/图像/区域/提示词/输出约束/超时）
     * @return 原始文本 + 通用树 + 用量 + 模型标识
     */
    @Override
    public GenerationResult vision(String vendorCode, VisionRequest request) {
        ChatModel chatModel = instanceRegistry.chatModel(vendorCode, request.getModelKey());
        ChatResponse response = chatModel.chat(toLc4jVisionRequest(request), requestOptions());
        return toGenerationResult(response, request.getModelKey());
    }

    /**
     * 向量模态：按厂商码与请求里的模型名取实例 → 组装 LC4j 请求 → 调用 → 映射出参。
     *
     * @param vendorCode 厂商码
     * @param request    向量入参（模型/文本/超时/批大小/requestId）
     * @return 向量列表 + 维度 + 模型回显 + requestId
     */
    @Override
    public EmbeddingResult embed(String vendorCode, EmbeddingRequest request) {
        EmbeddingModel embeddingModel = instanceRegistry.embeddingModel(vendorCode, request.getModel());
        return toEmbeddingResult(embeddingModel.embed(toLc4jEmbeddingRequest(request)), request);
    }

    /** 请求选项：已绑定调用上下文即带上属性表，未绑定取空选项（监听器无从记录，静默跳过） */
    private ChatRequestOptions requestOptions() {
        ModelCallTrace trace = modelCallTraceHolder.current();
        return NullUtil.isNull(trace) ? ChatRequestOptions.EMPTY
                : ChatRequestOptions.builder().addListenerAttribute(ModelCallTrace.ATTRIBUTE_KEY, trace).build();
    }

    /** 契约 → LC4j 请求：消息列表 + 输出约束（TEXT 不设 ResponseFormat） */
    private dev.langchain4j.model.chat.request.ChatRequest toLc4jRequest(ChatRequest request) {
        dev.langchain4j.model.chat.request.ChatRequest.Builder builder =
                dev.langchain4j.model.chat.request.ChatRequest.builder().messages(toLc4jMessages(request.getMessages()));
        ResponseFormat responseFormat = toResponseFormat(request.getOutput());
        if (NullUtil.isNotNull(responseFormat)) {
            builder.responseFormat(responseFormat);
        }
        return builder.build();
    }

    /** 契约 → LC4j 视觉请求：单条「图像 + 提示词」用户消息 + 输出约束（TEXT 不设 ResponseFormat） */
    private dev.langchain4j.model.chat.request.ChatRequest toLc4jVisionRequest(VisionRequest request) {
        dev.langchain4j.model.chat.request.ChatRequest.Builder builder =
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(List.of(toLc4jVisionMessage(request)));
        ResponseFormat responseFormat = toResponseFormat(request.getOutput());
        if (NullUtil.isNotNull(responseFormat)) {
            builder.responseFormat(responseFormat);
        }
        return builder.build();
    }

    /** 视觉用户消息：图像内容取自请求里的字节或引用，提示词缺失按空串处理 */
    private UserMessage toLc4jVisionMessage(VisionRequest request) {
        String prompt = NullUtil.isNull(request.getPrompt()) ? "" : request.getPrompt();
        return UserMessage.from(toImageContent(request), TextContent.from(prompt));
    }

    /**
     * 图像内容映射：请求带字节即按 base64 组装（媒体类型取请求里的 {@code mimeType}），带引用即按 URL 组装。
     * 字节与引用都缺即按「模型请求参数或内容被拒」拒绝；字节非空而媒体类型缺失同样拒绝，两种情形都不会发出请求。
     */
    private Content toImageContent(VisionRequest request) {
        Image.Builder image = Image.builder();
        byte[] bytes = request.getImageBytes();
        if (NullUtil.isNotNull(bytes) && bytes.length > 0) {
            image.base64Data(Base64.getEncoder().encodeToString(bytes)).mimeType(requireMimeType(request));
        } else if (NullUtil.isNotNull(request.getImageRef()) && !request.getImageRef().isBlank()) {
            image.url(request.getImageRef());
        } else {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "视觉入参缺图像：imageBytes 与 imageRef 均为空");
        }
        return ImageContent.from(image.build(), ImageContent.DetailLevel.AUTO);
    }

    /**
     * 图像字节的媒体类型：缺失即按「模型请求参数或内容被拒」拒绝，消息写明缺的是媒体类型。
     *
     * @param request 视觉入参（已确认携带图像字节）
     * @return 媒体类型
     */
    private String requireMimeType(VisionRequest request) {
        String mimeType = request.getMimeType();
        if (NullUtil.isNull(mimeType) || mimeType.isBlank()) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST,
                    "视觉入参缺媒体类型：携带 imageBytes 时必须给出 mimeType（如 image/png / image/jpeg）");
        }
        return mimeType;
    }

    /** 消息列表映射：SYSTEM → SystemMessage、ASSISTANT → AiMessage、USER 与未标注角色 → UserMessage */
    private List<dev.langchain4j.data.message.ChatMessage> toLc4jMessages(List<ChatMessage> messages) {
        List<dev.langchain4j.data.message.ChatMessage> lc4jMessages = new ArrayList<>();
        if (NullUtil.isNull(messages)) {
            return lc4jMessages;
        }
        for (ChatMessage message : messages) {
            lc4jMessages.add(toLc4jMessage(message));
        }
        return lc4jMessages;
    }

    /** 单条消息映射：内容缺失按空串处理 */
    private dev.langchain4j.data.message.ChatMessage toLc4jMessage(ChatMessage message) {
        String content = NullUtil.isNull(message.getContent()) ? "" : message.getContent();
        MessageRole role = message.getRole();
        if (MessageRole.SYSTEM == role) {
            return SystemMessage.from(content);
        }
        if (MessageRole.ASSISTANT == role) {
            return AiMessage.from(content);
        }
        return UserMessage.from(content);
    }

    /**
     * 输出约束映射：TEXT 返回 null（请求不带 ResponseFormat）、JSON_OBJECT 设 JSON、
     * JSON_SCHEMA 设 JSON 加完整 schema；JSON_SCHEMA 缺 schema 即抛「模型请求参数或内容被拒」。
     */
    private ResponseFormat toResponseFormat(OutputSpec output) {
        OutputFormat format = NullUtil.isNull(output) ? OutputFormat.TEXT : output.getFormat();
        if (NullUtil.isNull(format) || OutputFormat.TEXT == format) {
            return null;
        }
        if (OutputFormat.JSON_OBJECT == format) {
            return ResponseFormat.JSON;
        }
        String schema = output.getSchema();
        if (NullUtil.isNull(schema) || schema.isBlank()) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "JSON_SCHEMA 输出缺 schema");
        }
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name(JSON_SCHEMA_NAME).rootElement(JsonRawSchema.from(schema)).build())
                .build();
    }

    /**
     * 响应映射：content 取响应文本、usage 取 TokenUsage、model 取响应回传值（缺失时回填请求模型名）。
     * modelVersion 该版本响应不带，留 null。
     */
    private GenerationResult toGenerationResult(ChatResponse response, String modelKey) {
        GenerationResult result = new GenerationResult();
        AiMessage aiMessage = response.aiMessage();
        result.setContent(NullUtil.isNull(aiMessage) ? null : aiMessage.text());
        result.setUsage(ModelUsageMapper.toModelUsage(response.tokenUsage()));
        String modelName = response.modelName();
        result.setModel(NullUtil.isNull(modelName) ? modelKey : modelName);
        return result;
    }

    /** 向量请求映射：一批文本按请求顺序成为输入，模型名由实例构建时的 modelName 承载 */
    private dev.langchain4j.model.embedding.request.EmbeddingRequest toLc4jEmbeddingRequest(EmbeddingRequest request) {
        dev.langchain4j.model.embedding.request.EmbeddingRequest.Builder builder =
                dev.langchain4j.model.embedding.request.EmbeddingRequest.builder();
        for (String text : NullUtil.isNull(request.getTexts()) ? List.<String>of() : request.getTexts()) {
            builder.input(text);
        }
        return builder.build();
    }

    /**
     * 向量响应映射：向量列表、维度、模型回显与 requestId 四项。
     * model 取响应回传值（缺失时回填请求模型名），口径与对话模态一致。
     */
    private EmbeddingResult toEmbeddingResult(EmbeddingResponse response, EmbeddingRequest request) {
        List<dev.langchain4j.data.embedding.Embedding> data = response.embeddings();
        if (NullUtil.isNull(data) || data.isEmpty()) {
            throw new KnowledgeException(ErrorCode.MODEL_OUTPUT_INVALID, "向量化响应缺少向量数据");
        }
        List<List<Float>> embeddings = new ArrayList<>(data.size());
        for (dev.langchain4j.data.embedding.Embedding item : data) {
            embeddings.add(item.vectorAsList());
        }
        EmbeddingResult result = new EmbeddingResult();
        result.setEmbeddings(embeddings);
        result.setDimension(embeddings.getFirst().size());
        String modelName = response.modelName();
        result.setModel(NullUtil.isNull(modelName) ? request.getModel() : modelName);
        result.setRequestId(request.getRequestId());
        return result;
    }
}
