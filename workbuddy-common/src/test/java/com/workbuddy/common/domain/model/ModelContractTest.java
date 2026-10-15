package com.workbuddy.common.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 模态契约单测：构造与可空字段语义（ModelUsage 三项可空）、枚举取值集合。
 *
 * @author cxxl
 */
class ModelContractTest {

    @Test
    void outputSpecShouldDefaultToTextAndAcceptSchema() {
        OutputSpec spec = new OutputSpec();

        assertEquals(OutputFormat.TEXT, spec.getFormat());
        assertNull(spec.getSchema());

        spec.setFormat(OutputFormat.JSON_SCHEMA);
        spec.setSchema("{\"type\":\"object\"}");
        assertEquals(OutputFormat.JSON_SCHEMA, spec.getFormat());
        assertEquals("{\"type\":\"object\"}", spec.getSchema());

        assertArrayEquals(new OutputFormat[] {OutputFormat.TEXT, OutputFormat.JSON_OBJECT, OutputFormat.JSON_SCHEMA},
                OutputFormat.values());
    }

    @Test
    void messageRoleShouldCoverSystemUserAssistant() {
        assertArrayEquals(new MessageRole[] {MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT},
                MessageRole.values());
    }

    @Test
    void chatMessageShouldCarryRoleAndContent() {
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.USER);
        message.setContent("第一句");

        assertEquals(MessageRole.USER, message.getRole());
        assertEquals("第一句", message.getContent());
    }

    @Test
    void chatRequestShouldCarryModelMessagesOutputAndTimeout() {
        OutputSpec output = new OutputSpec();
        ChatRequest request = new ChatRequest();
        request.setModelKey("qwen-max");
        request.setMessages(List.of(new ChatMessage()));
        request.setOutput(output);
        request.setTimeoutMs(30000);

        assertEquals("qwen-max", request.getModelKey());
        assertEquals(1, request.getMessages().size());
        assertSame(output, request.getOutput());
        assertEquals(30000, request.getTimeoutMs());

        ChatRequest empty = new ChatRequest();
        assertNull(empty.getModelKey());
        assertNull(empty.getMessages());
        assertNull(empty.getOutput());
        assertNull(empty.getTimeoutMs());
    }

    @Test
    void imageRegionShouldCarryPageAndBbox() {
        ImageRegion region = new ImageRegion();
        region.setPageNo(2);
        region.setX0(1.5);
        region.setY0(2.5);
        region.setX1(30.0);
        region.setY1(40.0);

        assertEquals(2, region.getPageNo());
        assertEquals(1.5, region.getX0());
        assertEquals(40.0, region.getY1());
    }

    @Test
    void visionRequestShouldCarryImagePromptRegionAndOutput() {
        ImageRegion region = new ImageRegion();
        OutputSpec output = new OutputSpec();
        byte[] bytes = {1, 2, 3};
        VisionRequest request = new VisionRequest();
        request.setModelKey("qwen-vl-max");
        request.setImageBytes(bytes);
        request.setMimeType("image/png");
        request.setRegion(region);
        request.setPrompt("识别表格");
        request.setOutput(output);
        request.setTimeoutMs(60000);

        assertEquals("qwen-vl-max", request.getModelKey());
        assertNull(request.getImageRef());
        assertSame(bytes, request.getImageBytes());
        assertEquals("image/png", request.getMimeType());
        assertSame(region, request.getRegion());
        assertEquals("识别表格", request.getPrompt());
        assertSame(output, request.getOutput());
        assertEquals(60000, request.getTimeoutMs());
    }

    @Test
    void modelUsageTokensShouldStayNullable() {
        ModelUsage usage = new ModelUsage();

        assertNull(usage.getPromptTokens());
        assertNull(usage.getCompletionTokens());
        assertNull(usage.getTotalTokens());

        usage.setPromptTokens(12);
        usage.setCompletionTokens(34);
        usage.setTotalTokens(46);
        assertEquals(12, usage.getPromptTokens());
        assertEquals(34, usage.getCompletionTokens());
        assertEquals(46, usage.getTotalTokens());
    }

    @Test
    void generationResultShouldCarryTextTreeUsageAndModel() {
        ModelUsage usage = new ModelUsage();
        GenerationResult result = new GenerationResult();
        result.setContent("{\"a\":1}");
        result.setJson(Map.of("a", 1));
        result.setUsage(usage);
        result.setModel("qwen-max");
        result.setModelVersion("qwen-max-2024-09-19");

        assertEquals("{\"a\":1}", result.getContent());
        assertInstanceOf(Map.class, result.getJson());
        assertSame(usage, result.getUsage());
        assertEquals("qwen-max", result.getModel());
        assertEquals("qwen-max-2024-09-19", result.getModelVersion());

        GenerationResult empty = new GenerationResult();
        assertNull(empty.getJson());
        assertNull(empty.getUsage());
    }

    @Test
    void authTypeShouldHaveBearerOnly() {
        assertArrayEquals(new AuthType[] {AuthType.BEARER}, AuthType.values());
    }
}
