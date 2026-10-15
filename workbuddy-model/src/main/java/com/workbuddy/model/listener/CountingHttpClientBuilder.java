package com.workbuddy.model.listener;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;

import java.time.Duration;

/**
 * HTTP 客户端构建器装饰器：连接超时与读取超时都转给被包装的构建器，构建出来的客户端再包一层
 * {@link CountingHttpClient}，每次真实请求各计一次尝试。
 *
 * <p>被包装的构建器取 LC4j 的默认实现 —— {@link HttpClientBuilderLoader} 按服务加载挑选，
 * 本仓库的依赖里落到 JDK HttpClient 那一支（`langchain4j-http-client-jdk`）。每构建一个模型实例取一份新的
 * 构建器，超时状态不在实例之间串用。
 *
 * @author cxxl
 */
public class CountingHttpClientBuilder implements HttpClientBuilder {

    /** 被包装的构建器；设置超时后取该 setter 的返回值，链式语义与被包装的构建器保持一致 */
    private HttpClientBuilder delegate;

    /** 调用上下文持有，交给构建出来的客户端 */
    private final ModelCallTraceHolder traceHolder;

    /**
     * 构造装饰器：被包装的构建器取 LC4j 的默认实现。
     *
     * @param traceHolder 调用上下文持有
     */
    public CountingHttpClientBuilder(ModelCallTraceHolder traceHolder) {
        this(HttpClientBuilderLoader.loadHttpClientBuilder(), traceHolder);
    }

    /**
     * 构造装饰器：被包装的构建器由入参给出。
     *
     * @param delegate    被包装的构建器
     * @param traceHolder 调用上下文持有
     */
    CountingHttpClientBuilder(HttpClientBuilder delegate, ModelCallTraceHolder traceHolder) {
        this.delegate = delegate;
        this.traceHolder = traceHolder;
    }

    /** 连接超时：取被包装的构建器的值（LC4j 用它补默认值） */
    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    /** 连接超时：转给被包装的构建器，返回值留在本装饰器上（LC4j 用链式调用的终点取 `build()`） */
    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        delegate = delegate.connectTimeout(connectTimeout);
        return this;
    }

    /** 读取超时：取被包装的构建器的值（LC4j 用它补默认值） */
    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    /** 读取超时：转给被包装的构建器，返回值留在本装饰器上 */
    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        delegate = delegate.readTimeout(readTimeout);
        return this;
    }

    /** 构建：真实客户端由被包装的构建器产出，外面包一层计数装饰器 */
    @Override
    public HttpClient build() {
        return new CountingHttpClient(delegate.build(), traceHolder);
    }
}
