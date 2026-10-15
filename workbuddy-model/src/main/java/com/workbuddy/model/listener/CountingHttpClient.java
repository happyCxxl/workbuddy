package com.workbuddy.model.listener;

import com.workbuddy.common.utils.NullUtil;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * HTTP 客户端装饰器：每次真实请求前在当前线程的调用上下文里把尝试次数加一，阻塞入口另记该次尝试的耗时。
 *
 * <p>请求一律转给被包装的客户端，返回值与异常原样透出 —— 本层不重试、不改写异常、不吞异常，重试仍归 LC4j。
 * 当前线程没有绑定调用上下文即静默直通（不计数、不抛错）；服务端返回非 2xx 时计数已经完成，本次尝试照样计入。
 *
 * @author cxxl
 */
public class CountingHttpClient implements HttpClient {

    /** 被包装的客户端 */
    private final HttpClient delegate;

    /** 调用上下文持有：绑定与解绑都在调用线程内完成 */
    private final ModelCallTraceHolder traceHolder;

    /**
     * 构造装饰器。
     *
     * @param delegate    HTTP 客户端（LC4j 默认实现构建出来的那个）
     * @param traceHolder 调用上下文持有
     */
    public CountingHttpClient(HttpClient delegate, ModelCallTraceHolder traceHolder) {
        this.delegate = delegate;
        this.traceHolder = traceHolder;
    }

    /** 阻塞请求：计数与耗时都记在本入口 */
    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        ModelCallTrace trace = traceHolder.current();
        if (NullUtil.isNull(trace)) {
            return delegate.execute(request);
        }
        trace.increaseAttempts();
        long startedAt = System.nanoTime();
        try {
            return delegate.execute(request);
        } finally {
            trace.markAttemptLatency(System.nanoTime() - startedAt);
        }
    }

    /** 流式请求：同样计一次真实请求；该调用的返回时机与事件流生命周期相关，单次尝试耗时只在阻塞入口记录 */
    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        ModelCallTrace trace = traceHolder.current();
        if (NullUtil.isNotNull(trace)) {
            trace.increaseAttempts();
        }
        delegate.execute(request, parser, listener);
    }

    /** 异步入口：原样转发（完成回调在另外的线程上，本层取不到绑定的调用上下文，故不计数） */
    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
        return delegate.executeAsync(request);
    }

    /** 发布者入口：原样转发（请求在订阅时发出，发起线程不在本层的控制内，故不计数） */
    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
        return delegate.stream(request, parser);
    }
}
