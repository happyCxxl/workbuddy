package com.workbuddy.biz.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.workbuddy.common.dto.response.chunk.ChunkDetailVO;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 环节统计 JSON 契约单测：基类字段必须按运行时具体类型序列化，null 字段不出现。
 */
class StageStatsJsonTest {

    private final ObjectMapper objectMapper = new Jackson2ObjectMapperBuilder().build();

    /**
     * 执行树节点声明的是基类，实际放切片统计：JSON 必须带切片字段。
     */
    @Test
    void lineageStatsShouldSerializeRuntimeChunkFields() {
        LineageNodeVO node = new LineageNodeVO();
        node.setStats(StageStatsAssembler.fromChunk(Map.of("chunkCount", 3, "targetMaxLen", 200)));

        JsonNode stats = objectMapper.valueToTree(node).get("stats");

        assertEquals(3, stats.get("chunkCount").asInt());
        assertEquals(200, stats.get("targetMaxLen").asInt());
        assertFalse(stats.has("recordCount"));
    }

    /**
     * 详情用泛型绑定具体统计：stageStats 不能退化成空对象。
     */
    @Test
    void detailStatsShouldSerializeConcreteFields() {
        ChunkDetailVO detail = new ChunkDetailVO();
        detail.setStageStats(StageStatsAssembler.fromChunk(Map.of("chunkCount", 4)));

        JsonNode stats = objectMapper.valueToTree(detail).get("stageStats");

        assertEquals(4, stats.get("chunkCount").asInt());
    }

    /**
     * 原 map 中值为 null 的键：具体 VO 序列化时不出现。
     */
    @Test
    void nullValuedFieldShouldBeOmitted() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("chunkCount", 1);
        raw.put("durationMs", null);

        JsonNode stats = objectMapper.valueToTree(StageStatsAssembler.fromChunk(raw));

        assertEquals(1, stats.get("chunkCount").asInt());
        assertFalse(stats.has("durationMs"));
    }

    /**
     * 解析等无统计环节：空基类序列化为空对象。
     *
     * @throws Exception JSON 序列化失败
     */
    @Test
    void emptyStatsShouldSerializeAsEmptyObject() throws Exception {
        assertEquals("{}", objectMapper.writeValueAsString(StageStatsAssembler.from("PARSE", Map.of())));
    }

    /**
     * 非 null 值：旧 map 与新 VO 的 JSON 树完全一致。
     */
    @Test
    void nonNullMapAndVoShouldHaveSameJson() {
        Map<String, Object> raw = Map.of("chunkCount", 3, "targetMaxLen", 200, "fallbackCount", 1);

        JsonNode expected = objectMapper.valueToTree(raw);
        JsonNode actual = objectMapper.valueToTree(StageStatsAssembler.fromChunk(raw));

        assertEquals(expected, actual);
    }
}
