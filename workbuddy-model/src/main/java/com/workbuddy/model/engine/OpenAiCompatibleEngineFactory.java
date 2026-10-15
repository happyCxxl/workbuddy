package com.workbuddy.model.engine;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.listener.CountingHttpClientBuilder;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.listener.UsageRecordingListener;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * OpenAI 兼容引擎工厂：按厂商配置构建 LC4j 的对话模型与向量模型实例。
 * baseUrl 留空取 DashScope 兼容端点默认值，apiKey 与模型名逐项来自配置与请求，超时与重试口径见各类常量说明。
 * 两个构建方法都挂上用量监听器与 HTTP 客户端装饰器，用量、延迟与真实请求次数落在调用上下文里。
 *
 * @author cxxl
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleEngineFactory {

    /** baseUrl 留空时的默认地址（DashScope 兼容端点，实际地址以百炼控制台为准） */
    static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** timeout-ms 留空时的默认总超时（毫秒） */
    static final int DEFAULT_TIMEOUT_MS = 60000;

    /** 请求日志开关；不新增配置键，排障时在此临时打开 */
    private static final boolean LOG_REQUESTS = false;

    /** 响应日志开关；不新增配置键，排障时在此临时打开 */
    private static final boolean LOG_RESPONSES = false;

    /** 用量监听器：挂在对话模型与向量模型实例上，记录用量、延迟与错误 */
    private final UsageRecordingListener usageRecordingListener;

    /** 调用上下文持有：HTTP 客户端装饰器按它取当前线程的调用上下文 */
    private final ModelCallTraceHolder modelCallTraceHolder;

    /**
     * 对话模型实例：modelName 取模型名，timeout 取单一总超时，max-retries 配了才透传，
     * 用量监听器与 HTTP 客户端装饰器都挂在本方法内的 builder 上。
     *
     * @param vendor   厂商配置（baseUrl / apiKey / timeout-ms / max-retries）
     * @param modelKey 模型名
     * @return LC4j 对话模型实例
     */
    ChatModel chatModel(ModelVendorProperties.Vendor vendor, String modelKey) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder();
        builder.baseUrl(baseUrl(vendor));
        builder.apiKey(vendor.getApiKey());
        builder.modelName(modelKey);
        builder.timeout(timeout(vendor));
        builder.logRequests(LOG_REQUESTS);
        builder.logResponses(LOG_RESPONSES);
        builder.listeners(usageRecordingListener);
        builder.httpClientBuilder(countingHttpClientBuilder());
        applyMaxRetries(builder, vendor);
        return builder.build();
    }

    /**
     * 向量模型实例：构建参数口径与对话模型一致，用量监听器与 HTTP 客户端装饰器都挂在本方法内的 builder 上。
     *
     * @param vendor   厂商配置（baseUrl / apiKey / timeout-ms / max-retries）
     * @param modelKey 模型名
     * @return LC4j 向量模型实例
     */
    EmbeddingModel embeddingModel(ModelVendorProperties.Vendor vendor, String modelKey) {
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl(vendor))
                .apiKey(vendor.getApiKey())
                .modelName(modelKey)
                .timeout(timeout(vendor))
                .logRequests(LOG_REQUESTS)
                .logResponses(LOG_RESPONSES)
                .listeners(List.of(usageRecordingListener))
                .httpClientBuilder(countingHttpClientBuilder());
        applyMaxRetries(builder, vendor);
        return builder.build();
    }

    /** HTTP 客户端装饰器：每次构建各取一份，超时状态不在模型实例之间串用 */
    private CountingHttpClientBuilder countingHttpClientBuilder() {
        return new CountingHttpClientBuilder(modelCallTraceHolder);
    }

    /** 服务地址：配了取配置值，留空取 DashScope 兼容端点默认值 */
    private String baseUrl(ModelVendorProperties.Vendor vendor) {
        String baseUrl = vendor.getBaseUrl();
        return NullUtil.isNull(baseUrl) || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }

    /** 单一总超时：配了取配置值，留空取默认 60000 毫秒 */
    private Duration timeout(ModelVendorProperties.Vendor vendor) {
        Integer timeoutMs = vendor.getTimeoutMs();
        return Duration.ofMillis(NullUtil.isNull(timeoutMs) ? DEFAULT_TIMEOUT_MS : timeoutMs);
    }

    /** 重试次数：配了才透传，留空用 LC4j 默认值 */
    private void applyMaxRetries(OpenAiChatModel.OpenAiChatModelBuilder builder, ModelVendorProperties.Vendor vendor) {
        if (vendor.getMaxRetries() != null) {
            builder.maxRetries(vendor.getMaxRetries());
        }
    }

    /** 重试次数：配了才透传，留空用 LC4j 默认值 */
    private void applyMaxRetries(OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder,
                                 ModelVendorProperties.Vendor vendor) {
        if (vendor.getMaxRetries() != null) {
            builder.maxRetries(vendor.getMaxRetries());
        }
    }
}
