package com.workbuddy.biz.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbuddy.common.dto.response.chunk.ChunkDetailVO;
import com.workbuddy.common.dto.response.chunk.ChunkStatsVO;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 环节统计 JSON 契约单测：基类字段按运行时具体类型序列化，null 字段不出现。
 */
class StageStatsJsonTest {

    private final ObjectMapper objectMapper = new Jackson2ObjectMapperBuilder().build();

    /**
     * 执行树节点声明的是基类，实际放切片统计：JSON 必须带切片字段。
     *
     * <p>守护 JAVA-DTO-005：基类字段退化成 Object/空对象即失败。
     */
    @Test
    void lineageStatsShouldSerializeRuntimeChunkFields() {
        LineageNodeVO node = new LineageNodeVO();
        ChunkStatsVO chunkStats = new ChunkStatsVO();
        chunkStats.setChunkCount(3);
        chunkStats.setTargetMaxLen(200);
        node.setStats(chunkStats);

        JsonNode stats = objectMapper.valueToTree(node).get("stats");

        assertEquals(3, stats.get("chunkCount").asInt());
        assertEquals(200, stats.get("targetMaxLen").asInt());
        assertFalse(stats.has("recordCount"));
    }

    /**
     * 详情用泛型绑定具体统计：stageStats 不能退化成空对象。
     *
     * <p>守护 JAVA-DTO-005：详情统计类型丢失即失败。
     */
    @Test
    void detailStatsShouldSerializeConcreteFields() {
        ChunkDetailVO detail = new ChunkDetailVO();
        ChunkStatsVO chunkStats = new ChunkStatsVO();
        chunkStats.setChunkCount(4);
        detail.setStageStats(chunkStats);

        JsonNode stats = objectMapper.valueToTree(detail).get("stageStats");

        assertEquals(4, stats.get("chunkCount").asInt());
    }

    /**
     * null 字段：具体 VO 序列化时不出现。
     *
     * <p>守护 JAVA-DTO-005：null 字段不应退回通用 Map 形状。
     */
    @Test
    void nullValuedFieldShouldBeOmitted() {
        ChunkStatsVO stats = new ChunkStatsVO();
        stats.setChunkCount(1);

        JsonNode json = objectMapper.valueToTree(stats);

        assertEquals(1, json.get("chunkCount").asInt());
        assertFalse(json.has("durationMs"));
    }

    /**
     * 解析等无统计环节：空基类序列化为空对象。
     *
     * <p>守护 JAVA-DTO-005：无统计时不能序列化失败或退回 Map。
     *
     * @throws Exception JSON 序列化失败
     */
    @Test
    void emptyStatsShouldSerializeAsEmptyObject() throws Exception {
        assertEquals("{}", objectMapper.writeValueAsString(new StageStatsVO()));
    }

    /**
     * 非 null 值：具体 VO 的 JSON 与同形状 map 完全一致。
     *
     * <p>守护 JAVA-DTO-005：类型化不能改变 JSON 形状。
     */
    @Test
    void concreteVoShouldHaveSameJsonAsMapShape() {
        Map<String, Object> expected = Map.of("chunkCount", 3, "targetMaxLen", 200, "fallbackCount", 1);
        ChunkStatsVO stats = new ChunkStatsVO();
        stats.setChunkCount(3);
        stats.setTargetMaxLen(200);
        stats.setFallbackCount(1);

        assertEquals(objectMapper.valueToTree(expected), objectMapper.valueToTree(stats));
    }
}
