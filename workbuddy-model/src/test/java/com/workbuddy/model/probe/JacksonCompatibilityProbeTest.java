package com.workbuddy.model.probe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jackson 版本兼容性用例：按当前依赖解析结果（Boot 管理的 Jackson 2.15.4）真调 LC4j 的对话接口与向量接口，
 * 覆盖请求组装（含 response_format）、响应解析（含 usage）与用量读取，出现 NoSuchMethodError 一类链接错误即失败。
 *
 * @author cxxl
 */
class JacksonCompatibilityProbeTest {

    private final AtomicReference<String> chatBody = new AtomicReference<>();
    private final AtomicReference<String> embedBody = new AtomicReference<>();

    @Test
    void chatAgainstFakeEndpointShouldReturnContentAndUsage() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, chatJson());
        });
        server.createContext("/v1/embeddings", exchange -> {
            embedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, embedJson());
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ChatModel chatModel = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey("sk-probe")
                    .modelName("qwen-max")
                    .timeout(Duration.ofSeconds(10))
                    .logRequests(true)
                    .logResponses(true)
                    .build();

            assertEquals("pong", chatModel.chat("ping"));
            assertEquals(3, chatModel.chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(dev.langchain4j.data.message.UserMessage.from("ping"))
                    .responseFormat(ResponseFormat.JSON)
                    .build()).tokenUsage().inputTokenCount());
            assertTrue(chatBody.get().contains("\"response_format\""), chatBody.get());

            EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey("sk-probe")
                    .modelName("text-embedding-v4")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            List<Embedding> embeddings = embeddingModel.embedAll(List.of(TextSegment.from("甲"))).content();

            assertEquals(1, embeddings.size());
            assertEquals(2, embeddings.getFirst().dimension());
            assertTrue(embedBody.get().contains("text-embedding-v4"), embedBody.get());
        } finally {
            server.stop(0);
        }
    }

    private String chatJson() {
        return "{\"id\":\"probe-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"qwen-max\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,"
                + "\"total_tokens\":5}}";
    }

    private String embedJson() {
        return "{\"object\":\"list\",\"model\":\"text-embedding-v4\",\"data\":[{\"object\":\"embedding\","
                + "\"index\":0,\"embedding\":[0.1,0.2]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
