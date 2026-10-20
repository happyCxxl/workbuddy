package com.workbuddy.biz.service.support;

import com.workbuddy.common.dto.response.chunk.ChunkStatsVO;
import com.workbuddy.common.dto.response.embed.EmbedStatsVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessStatsVO;
import com.workbuddy.common.dto.response.structure.StructureStatsVO;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import com.workbuddy.biz.service.support.stats.StructureStatsSupport;
import com.workbuddy.biz.service.support.stats.PreprocessStatsSupport;
import com.workbuddy.biz.service.support.stats.ChunkStatsSupport;
import com.workbuddy.biz.service.support.assemble.StageStatsAssembler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环节统计组装器单测：每个支撑类的统计键都必须落到对应环节 VO 的同名字段。
 */
class StageStatsAssemblerTest {

    /**
     * 切片：支撑类的全部 KEY_* 键逐项映射，类型不丢。
     *
     * <p>守护 JAVA-DTO-005：漏映射或退回 Object 即失败。
     *
     * @throws Exception 反射读取字段失败
     */
    @Test
    void chunkShouldMapEverySupportKey() throws Exception {
        Map<String, Object> stats = sampleStats(ChunkStatsVO.class, ChunkStatsSupport.class);

        ChunkStatsVO vo = StageStatsAssembler.fromChunk(stats);

        assertMapped(vo, stats);
    }

    /**
     * 预处理：支撑键加上 step_log 聚合的 matched/changed 都必须映射。
     *
     * <p>守护 JAVA-DTO-005：漏映射或退回 Object 即失败。
     *
     * @throws Exception 反射读取字段失败
     */
    @Test
    void preprocessShouldMapEverySupportKeyAndStepCounts() throws Exception {
        Map<String, Object> stats = sampleStats(PreprocessStatsVO.class, PreprocessStatsSupport.class);
        stats.put("matched", 3L);
        stats.put("changed", 2L);

        PreprocessStatsVO vo = StageStatsAssembler.fromPreprocess(stats);

        assertMapped(vo, stats);
    }

    /**
     * 组装：支撑类的全部 KEY_* 键逐项映射。
     *
     * <p>守护 JAVA-DTO-005：漏映射或退回 Object 即失败。
     *
     * @throws Exception 反射读取字段失败
     */
    @Test
    void structureShouldMapEverySupportKey() throws Exception {
        Map<String, Object> stats = sampleStats(StructureStatsVO.class, StructureStatsSupport.class);

        StructureStatsVO vo = StageStatsAssembler.fromStructure(stats);

        assertMapped(vo, stats);
    }

    /**
     * 向量化：集合表聚合的 recordCount/cachedCount 映射。
     *
     * <p>守护 JAVA-DTO-005：EmbedStatsVO 字段漏映射即失败。
     */
    @Test
    void embedShouldMapCollectionCounts() {
        EmbedStatsVO vo = StageStatsAssembler.fromEmbed(Map.of("recordCount", 5, "cachedCount", 2));

        assertEquals(5, vo.getRecordCount());
        assertEquals(2, vo.getCachedCount());
    }

    /**
     * 分派：按环节返回具体子类；解析返回空基类；缺 map 保持 null。
     *
     * <p>守护 JAVA-DTO-005：分派到 Object/错误子类即失败。
     */
    @Test
    void fromShouldDispatchByStageAndHandleMissingStats() {
        assertTrue(StageStatsAssembler.from("CHUNK", Map.of()) instanceof ChunkStatsVO);
        assertTrue(StageStatsAssembler.from("PREPROCESS", Map.of()) instanceof PreprocessStatsVO);
        assertTrue(StageStatsAssembler.from("STRUCTURE", Map.of()) instanceof StructureStatsVO);
        assertTrue(StageStatsAssembler.from("EMBED", Map.of()) instanceof EmbedStatsVO);
        assertEquals(StageStatsVO.class, StageStatsAssembler.from("PARSE", Map.of()).getClass());
        assertEquals(StageStatsVO.class, StageStatsAssembler.from(null, Map.of()).getClass());
        assertNull(StageStatsAssembler.from("CHUNK", null));
        assertNull(StageStatsAssembler.from(null, null));
    }

    /** 按支撑类的 KEY_* 常量造一份样本 map，值类型取对应 VO 字段类型。 */
    private static Map<String, Object> sampleStats(Class<?> voType, Class<?> supportType)
            throws IllegalAccessException, NoSuchFieldException {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (Field constant : supportType.getDeclaredFields()) {
            if (!constant.getName().startsWith("KEY_") || constant.getType() != String.class) {
                continue;
            }
            String key = (String) constant.get(null);
            stats.put(key, sampleValue(voType.getDeclaredField(key).getType()));
        }
        return stats;
    }

    /** 逐字段断言样本 map 已落到 VO。 */
    private static void assertMapped(StageStatsVO vo, Map<String, Object> stats) throws Exception {
        for (Map.Entry<String, Object> entry : stats.entrySet()) {
            Field field = vo.getClass().getDeclaredField(entry.getKey());
            field.setAccessible(true);
            assertEquals(entry.getValue(), field.get(vo), entry.getKey());
        }
    }

    /** 按字段类型给样本值。 */
    private static Object sampleValue(Class<?> type) {
        if (type == Integer.class) {
            return 7;
        }
        if (type == Long.class) {
            return 11L;
        }
        if (type == String.class) {
            return "v1";
        }
        if (Map.class.isAssignableFrom(type)) {
            return Map.of("A", 1);
        }
        throw new IllegalArgumentException("未覆盖的统计字段类型: " + type.getName());
    }
}
