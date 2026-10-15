package com.workbuddy.model.listener;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HTTP 计数装饰器单测（假客户端，不触网）：绑定上下文时每次请求计一次、未绑定时静默直通、
 * 异常原样透出、流式与异步入口原样转发、构建器的超时转发与装饰结果。
 *
 * @author cxxl
 */
class CountingHttpClientTest {

    private static final HttpRequest REQUEST = HttpRequest.builder()
            .method(HttpMethod.POST)
            .url("http://127.0.0.1:1/v1/chat/completions")
            .body("{}")
            .build();

    private ModelCallTraceHolder traceHolder;

    private FakeHttpClient delegate;

    private CountingHttpClient client;

    @BeforeEach
    void setUp() {
        traceHolder = new ModelCallTraceHolder();
        delegate = new FakeHttpClient();
        client = new CountingHttpClient(delegate, traceHolder);
    }

    @Test
    void boundTraceShouldCountRequestAndRecordAttemptLatency() {
        ModelCallTrace trace = new ModelCallTrace();
        traceHolder.bind(trace);

        SuccessfulHttpResponse response = client.execute(REQUEST);

        assertEquals(200, response.statusCode());
        assertEquals(1, delegate.blockingCalls.get());
        assertEquals(1, trace.attempts());
        assertEquals(0, trace.retryCount());
        assertNotNull(trace.lastAttemptLatencyMs());
    }

    @Test
    void boundTraceShouldCountEachRetriedRequest() {
        ModelCallTrace trace = new ModelCallTrace();
        traceHolder.bind(trace);

        client.execute(REQUEST);
        client.execute(REQUEST);
        client.execute(REQUEST);

        assertEquals(3, trace.attempts());
        assertEquals(2, trace.retryCount());
    }

    @Test
    void unboundTraceShouldPassThroughWithoutCounting() {
        SuccessfulHttpResponse response = client.execute(REQUEST);

        assertEquals(200, response.statusCode());
        assertEquals(1, delegate.blockingCalls.get());
        assertNull(traceHolder.current());
    }

    @Test
    void failureShouldPropagateUnchangedAndStillCount() {
        ModelCallTrace trace = new ModelCallTrace();
        traceHolder.bind(trace);
        RuntimeException failure = new RuntimeException("boom");
        delegate.failure.set(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.execute(REQUEST));

        assertSame(failure, thrown);
        assertEquals(1, trace.attempts());
        assertNotNull(trace.lastAttemptLatencyMs());
    }

    @Test
    void streamingEntryShouldCountOnceAndForward() {
        ModelCallTrace trace = new ModelCallTrace();
        traceHolder.bind(trace);
        ServerSentEventListener listener = error -> { };

        client.execute(REQUEST, new DefaultServerSentEventParser(), listener);

        assertEquals(1, delegate.streamingCalls.get());
        assertSame(listener, delegate.lastListener.get());
        assertEquals(1, trace.attempts());
        assertNull(trace.lastAttemptLatencyMs());
    }

    @Test
    void asyncAndPublisherEntriesShouldForward() {
        client.executeAsync(REQUEST);
        client.stream(REQUEST, new DefaultServerSentEventParser());

        assertEquals(1, delegate.asyncCalls.get());
        assertEquals(1, delegate.publisherCalls.get());
    }

    @Test
    void builderShouldForwardTimeoutsAndDecorateBuiltClient() {
        FakeHttpClientBuilder delegateBuilder = new FakeHttpClientBuilder();
        CountingHttpClientBuilder builder = new CountingHttpClientBuilder(delegateBuilder, traceHolder);

        assertSame(builder, builder.connectTimeout(Duration.ofSeconds(3)));
        assertSame(builder, builder.readTimeout(Duration.ofSeconds(5)));
        assertEquals(Duration.ofSeconds(3), delegateBuilder.connectTimeout());
        assertEquals(Duration.ofSeconds(5), delegateBuilder.readTimeout());
        assertEquals(Duration.ofSeconds(3), builder.connectTimeout());
        assertEquals(Duration.ofSeconds(5), builder.readTimeout());

        HttpClient built = builder.build();

        assertInstanceOf(CountingHttpClient.class, built);
        assertEquals(200, built.execute(REQUEST).statusCode());
    }

    /** 假 HTTP 客户端：记录各入口的调用次数，应答与失败按设定返回 */
    private static final class FakeHttpClient implements HttpClient {

        private final AtomicInteger blockingCalls = new AtomicInteger();

        private final AtomicInteger streamingCalls = new AtomicInteger();

        private final AtomicInteger asyncCalls = new AtomicInteger();

        private final AtomicInteger publisherCalls = new AtomicInteger();

        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        private final AtomicReference<ServerSentEventListener> lastListener = new AtomicReference<>();

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            blockingCalls.incrementAndGet();
            RuntimeException error = failure.get();
            if (error != null) {
                throw error;
            }
            return SuccessfulHttpResponse.builder().statusCode(200).body("{}").build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            streamingCalls.incrementAndGet();
            lastListener.set(listener);
        }

        @Override
        public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
            asyncCalls.incrementAndGet();
            return CompletableFuture.completedFuture(execute(request));
        }

        @Override
        public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
            publisherCalls.incrementAndGet();
            return subscriber -> { };
        }
    }

    /** 假 HTTP 客户端构建器：记录超时并产出假客户端 */
    private static final class FakeHttpClientBuilder implements HttpClientBuilder {

        private Duration connectTimeout;

        private Duration readTimeout;

        @Override
        public Duration connectTimeout() {
            return connectTimeout;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        @Override
        public Duration readTimeout() {
            return readTimeout;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        @Override
        public HttpClient build() {
            return new FakeHttpClient();
        }
    }
}
