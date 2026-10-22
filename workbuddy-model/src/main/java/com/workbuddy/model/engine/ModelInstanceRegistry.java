package com.workbuddy.model.engine;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.config.ModelVendorProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型实例注册表：按「厂商码 + 模型名」懒构建并缓存 LC4j 实例。
 *
 * <p>厂商连接取配置（{@code workbuddy.model.vendors.*}）；缺失厂商或密钥为空在构建期拒绝，
 * 超时与重试是实例级参数，调用方不另加重试。
 */
@Component
@RequiredArgsConstructor
public class ModelInstanceRegistry {

    /** 厂商配置缺省超时（毫秒），与配置口径一致 */
    private static final int DEFAULT_TIMEOUT_MS = 60000;

    private final ModelVendorProperties vendorProperties;

    private final Map<ModelRef, ChatModel> chatModels = new ConcurrentHashMap<>();

    private final Map<ModelRef, EmbeddingModel> embeddingModels = new ConcurrentHashMap<>();

    /**
     * 取对话实例（懒构建、命中复用）。
     *
     * @param model 目录行
     * @return 对话模型实例
     */
    public ChatModel chat(ModelDescriptor model) {
        ModelRef ref = refOf(model);
        return chatModels.computeIfAbsent(ref, this::buildChat);
    }

    /**
     * 取向量实例（懒构建、命中复用）。
     *
     * @param model 目录行
     * @return 向量模型实例
     */
    public EmbeddingModel embedding(ModelDescriptor model) {
        ModelRef ref = refOf(model);
        return embeddingModels.computeIfAbsent(ref, this::buildEmbedding);
    }

    /** 目录行 → 运行时定位；模型名为空按参数错误拒绝 */
    private ModelRef refOf(ModelDescriptor model) {
        if (NullUtil.isNull(model) || StrUtil.isBlank(model.getModelKey())) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "模型名为空，无法构建实例");
        }
        return new ModelRef(model.getVendorCode(), model.getModelKey());
    }

    /** 构建对话实例：baseUrl 留空走 LC4j 缺省地址 */
    private ChatModel buildChat(ModelRef ref) {
        ModelVendorProperties.Vendor vendor = requireVendor(ref);
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(vendor.getApiKey())
                .modelName(ref.modelKey())
                .timeout(Duration.ofMillis(timeoutOf(vendor)));
        if (StrUtil.isNotBlank(vendor.getBaseUrl())) {
            builder.baseUrl(vendor.getBaseUrl());
        }
        if (NullUtil.isNotNull(vendor.getMaxRetries())) {
            builder.maxRetries(vendor.getMaxRetries());
        }
        return builder.build();
    }

    /** 构建向量实例 */
    private EmbeddingModel buildEmbedding(ModelRef ref) {
        ModelVendorProperties.Vendor vendor = requireVendor(ref);
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .apiKey(vendor.getApiKey())
                .modelName(ref.modelKey())
                .timeout(Duration.ofMillis(timeoutOf(vendor)));
        if (StrUtil.isNotBlank(vendor.getBaseUrl())) {
            builder.baseUrl(vendor.getBaseUrl());
        }
        if (NullUtil.isNotNull(vendor.getMaxRetries())) {
            builder.maxRetries(vendor.getMaxRetries());
        }
        return builder.build();
    }

    /** 厂商必须已配置且密钥可解析；否则运行期拒绝 */
    private ModelVendorProperties.Vendor requireVendor(ModelRef ref) {
        Map<String, ModelVendorProperties.Vendor> vendors = vendorProperties.getVendors();
        ModelVendorProperties.Vendor vendor = NullUtil.isNull(vendors) ? null : vendors.get(ref.vendorCode());
        if (NullUtil.isNull(vendor) || StrUtil.isBlank(vendor.getApiKey())) {
            throw new KnowledgeException(ErrorCode.MODEL_VENDOR_UNKNOWN,
                    "厂商未配置或缺少 api-key：" + ref.vendorCode());
        }
        return vendor;
    }

    /** 超时取配置，留空用缺省 60 秒 */
    private int timeoutOf(ModelVendorProperties.Vendor vendor) {
        return NullUtil.isNull(vendor.getTimeoutMs()) ? DEFAULT_TIMEOUT_MS : vendor.getTimeoutMs();
    }
}
