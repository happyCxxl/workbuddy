package com.workbuddy.model.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地假 OpenAI 兼容端点（测试手段）：记录请求路径、Authorization 头与请求体，按设定状态码、响应体与延迟作答。
 * 对话与向量两个路径各挂一个处理器，同一份应答配置对两者生效；可指定前若干次请求按失败应答，其余按正常应答。
 *
 * @author cxxl
 */
public final class FakeOpenAiServer implements AutoCloseable {

    /** 对话路径 */
    public static final String CHAT_PATH = "/v1/chat/completions";

    /** 向量路径 */
    public static final String EMBEDDING_PATH = "/v1/embeddings";

    private final HttpServer server;

    private final AtomicInteger requestCount = new AtomicInteger();

    private final AtomicReference<String> lastPath = new AtomicReference<>();

    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private volatile int statusCode = 200;

    private volatile String responseBody = "{}";

    private volatile long delayMillis;

    /** 前若干次请求按失败应答作答的次数 */
    private volatile int failureCount;

    private volatile int failureStatusCode = 500;

    private volatile String failureBody = "{}";

    public FakeOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CHAT_PATH, this::handle);
        server.createContext(EMBEDDING_PATH, this::handle);
        server.start();
    }

    /** 服务地址（LC4j 的 baseUrl 口径，末尾不带斜杠） */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 已收到的请求数 */
    public int requestCount() {
        return requestCount.get();
    }

    /** 最近一次请求的路径 */
    public String lastPath() {
        return lastPath.get();
    }

    /** 最近一次请求的 Authorization 头 */
    public String lastAuthorization() {
        return lastAuthorization.get();
    }

    /** 最近一次请求的请求体 */
    public String lastBody() {
        return lastBody.get();
    }

    /** 设定应答状态码与响应体 */
    public void respondWith(int statusCode, String responseBody) {
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /** 设定应答延迟（毫秒） */
    public void delayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    /** 设定前若干次请求的应答；其余请求按 respondWith 的应答配置作答（重试链路用例） */
    public void failFirst(int failures, int statusCode, String responseBody) {
        this.failureCount = failures;
        this.failureStatusCode = statusCode;
        this.failureBody = responseBody;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        int index = requestCount.incrementAndGet();
        lastPath.set(exchange.getRequestURI().getPath());
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (delayMillis > 0) {
            sleep(delayMillis);
        }
        boolean failed = index <= failureCount;
        int status = failed ? failureStatusCode : statusCode;
        String body = failed ? failureBody : responseBody;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
