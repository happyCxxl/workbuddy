package com.workbuddy.model.listener;

import com.workbuddy.common.domain.model.ModelUsage;
import com.workbuddy.common.utils.NullUtil;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelErrorContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.embedding.listener.EmbeddingModelRequestContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelResponseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 用量监听器：同时挂在对话模型与向量模型实例上，记录一次调用的用量、延迟与错误，并按级别打印一行。
 * 只观察调用过程，不改变调用结果；取不到调用上下文即静默跳过。明细落库归计量片。
 *
 * <p>调用上下文有两条来路：对话侧由适配器放进请求选项的属性表（监听器按同一个键取回）；
 * 向量侧的请求没有属性表入口，改由调用上下文持有器按当前线程取回，两条来路都取不到即跳过。
 *
 * <p>尝试次数不在这里计：LC4j 的重试发生在一次调用内部，本监听器的回调一次调用只触发一次，
 * 计数落在 HTTP 客户端装饰器 {@link CountingHttpClient} 上。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageRecordingListener implements ChatModelListener, EmbeddingModelListener {

    /** 调用上下文持有：向量侧调用按当前线程取回上下文 */
    private final ModelCallTraceHolder modelCallTraceHolder;

    /** 对话请求到达：记录首次请求的时间戳；尝试次数由 HTTP 客户端装饰器写入 */
    @Override
    public void onRequest(ChatModelRequestContext context) {
        markRequestStart(trace(context.attributes()));
    }

    /** 对话响应到达：写入用量与延迟 */
    @Override
    public void onResponse(ChatModelResponseContext context) {
        recordSuccess(trace(context.attributes()), ModelUsageMapper.toModelUsage(context.chatResponse().tokenUsage()));
    }

    /** 对话出错：写入延迟与原始异常，错误码的映射由调用器完成 */
    @Override
    public void onError(ChatModelErrorContext context) {
        recordFailure(trace(context.attributes()), context.error());
    }

    /** 向量请求到达：记录首次请求的时间戳 */
    @Override
    public void onRequest(EmbeddingModelRequestContext context) {
        markRequestStart(trace(context.attributes()));
    }

    /** 向量响应到达：写入用量与延迟；向量应答只回提示词一项，缺失的项留 null */
    @Override
    public void onResponse(EmbeddingModelResponseContext context) {
        recordSuccess(trace(context.attributes()),
                ModelUsageMapper.toModelUsage(context.embeddingResponse().tokenUsage()));
    }

    /** 向量出错：写入延迟与原始异常 */
    @Override
    public void onError(EmbeddingModelErrorContext context) {
        recordFailure(trace(context.attributes()), context.error());
    }

    /** 记录首次请求的时间戳 */
    private void markRequestStart(ModelCallTrace trace) {
        if (NullUtil.isNull(trace)) {
            return;
        }
        trace.markRequestStart();
    }

    /** 记录用量与整段延迟 */
    private void recordSuccess(ModelCallTrace trace, ModelUsage usage) {
        if (NullUtil.isNull(trace)) {
            return;
        }
        trace.setUsage(usage);
        trace.markLatency();
        log.info("模型调用成功，尝试次数=[{}]，重试次数=[{}]，延迟毫秒=[{}]，最近尝试毫秒=[{}]，用量=[{}]",
                trace.attempts(), trace.retryCount(), trace.latencyMs(), trace.lastAttemptLatencyMs(), trace.usage());
    }

    /** 记录延迟与原始异常 */
    private void recordFailure(ModelCallTrace trace, Throwable error) {
        if (NullUtil.isNull(trace)) {
            return;
        }
        trace.setError(error);
        trace.markLatency();
        log.warn("模型调用失败，尝试次数=[{}]，重试次数=[{}]，延迟毫秒=[{}]，最近尝试毫秒=[{}]，错误=[{}]",
                trace.attempts(), trace.retryCount(), trace.latencyMs(), trace.lastAttemptLatencyMs(), trace.error());
    }

    /** 调用上下文：先按属性表取，取不到再按当前线程取；两处都没有即返回 null */
    private ModelCallTrace trace(Map<Object, Object> attributes) {
        Object value = NullUtil.isNull(attributes) ? null : attributes.get(ModelCallTrace.ATTRIBUTE_KEY);
        if (value instanceof ModelCallTrace current) {
            return current;
        }
        return modelCallTraceHolder.current();
    }
}
