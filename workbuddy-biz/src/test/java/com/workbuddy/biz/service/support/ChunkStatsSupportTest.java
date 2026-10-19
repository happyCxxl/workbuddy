package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片统计与结论单测。
 *
 * <p>守护 {@code stats} 的口径（类型分布、片长边界、父子与孤儿、来源计数、策略参数）与
 * {@code summary} 的陈述规则：边界比较写错、计数漏算、结论漏项都会让执行树显示假数据。
 */
class ChunkStatsSupportTest {

    /**
     * 产物缺失时不陈述统计。
     *
     * <p>守护 {@code stats} 的空产物兜底：返回空表会让详情页显示 0 片而不是「不可读」。
     */
    @Test
    void statsShouldReturnNullWhenChunkSetMissing() {
        assertNull(ChunkStatsSupport.stats(null, null, null, null, null));
    }

    /**
     * 类型分布、父子与孤儿、片长边界逐项核对。
     *
     * <p>守护 {@code putTypeCounts}/{@code putLengthStats}：边界取等（正好等于上限/下限）判错即失败。
     */
    @Test
    void statsShouldAggregateTypesLengthsParentsAndOrphans() {
        ChunkSet chunkSet = chunkSet(
                chunk("chunk-0001", ChunkContentType.SECTION, 500, null, List.of("e1")),
                chunk("chunk-0002", ChunkContentType.PARAGRAPH, 100, "chunk-0001", List.of("e1", "e2")),
                chunk("chunk-0003", ChunkContentType.TABLE, 300, null, List.of()),
                chunk("chunk-0004", ChunkContentType.IMAGE, 30, null, List.of("e1")),
                chunk("chunk-0005", ChunkContentType.FALLBACK, 10, null, List.of("e1", "e2", "e3")),
                chunk("chunk-0006", ChunkContentType.SECTION, 5, null, List.of()));

        Map<String, Object> stats = ChunkStatsSupport.stats(null, null, chunkSet, strategy(), null);

        assertEquals(6, stats.get(ChunkStatsSupport.KEY_CHUNK_COUNT));
        assertEquals(2, stats.get(ChunkStatsSupport.KEY_SECTION_COUNT));
        assertEquals(1, stats.get(ChunkStatsSupport.KEY_PARAGRAPH_COUNT));
        assertEquals(1, stats.get(ChunkStatsSupport.KEY_TABLE_COUNT));
        assertEquals(1, stats.get(ChunkStatsSupport.KEY_IMAGE_COUNT));
        assertEquals(1, stats.get(ChunkStatsSupport.KEY_FALLBACK_COUNT));
        assertEquals(1, stats.get(ChunkStatsSupport.KEY_PARENT_COUNT));
        // 孤儿 = 无父片且自身不是父片：表格 / 图片 / 兜底 三片
        assertEquals(3, stats.get(ChunkStatsSupport.KEY_ORPHAN_COUNT));
        assertEquals(945, stats.get(ChunkStatsSupport.KEY_TOTAL_CHARS));
        assertEquals(158, stats.get(ChunkStatsSupport.KEY_AVG_CHARS));
        assertEquals(500, stats.get(ChunkStatsSupport.KEY_MAX_CHARS));
        // 目标上限 100：正好 100 的片不算超；软上限 200：500 与 300 超
        assertEquals(100, stats.get(ChunkStatsSupport.KEY_TARGET_MAX_LEN));
        assertEquals(200, stats.get(ChunkStatsSupport.KEY_SOFT_MAX_LEN));
        assertEquals(2, stats.get(ChunkStatsSupport.KEY_OVER_TARGET_MAX_COUNT));
        assertEquals(2, stats.get(ChunkStatsSupport.KEY_OVER_SOFT_MAX_COUNT));
        // 欠长 30：正好 30 的片不算欠；父片（SECTION）不参与欠长统计
        assertEquals(30, stats.get(ChunkStatsSupport.KEY_MIN_MERGE_LEN));
        assertEquals(1, stats.get(ChunkStatsSupport.KEY_UNDER_MIN_MERGE_COUNT));
        assertEquals(50, stats.get(ChunkStatsSupport.KEY_FALLBACK_LEN));
        assertEquals(5, stats.get(ChunkStatsSupport.KEY_FALLBACK_OVERLAP));
        // 来源元素：多来源 2 片（2 个与 3 个来源），平均 (1+2+0+1+3+0)/6 ≈ 1.17 → 1
        assertEquals(2, stats.get(ChunkStatsSupport.KEY_MULTI_SOURCE_CHUNK_COUNT));
        assertEquals(1, stats.get(ChunkStatsSupport.KEY_AVG_SOURCE_ELEMENT_COUNT));
    }

    /**
     * 上游视图给出「进入切片 / 跳过」元素数与耗时。
     *
     * <p>守护 {@code putRoutingStats}：跳过态漏算会把被剔除的元素也算成切片输入。
     */
    @Test
    void statsShouldCountRoutingAndDuration() {
        ChunkSet chunkSet = chunkSet(chunk("chunk-0001", ChunkContentType.PARAGRAPH, 10, null, List.of("e1")));
        PreprocessView view = view(element("e1", ViewElementStatus.NORMAL),
                element("e2", ViewElementStatus.EXCLUDED_HEADER),
                element("e3", ViewElementStatus.REPEATED),
                element("e4", ViewElementStatus.NORMAL));
        LocalDateTime started = LocalDateTime.of(2024, 5, 1, 10, 0, 0);
        LocalDateTime finished = started.plusSeconds(2);

        Map<String, Object> stats = ChunkStatsSupport.stats(started, finished, chunkSet, strategy(), view);

        assertEquals(2, stats.get(ChunkStatsSupport.KEY_SKIPPED_ELEMENT_COUNT));
        assertEquals(2, stats.get(ChunkStatsSupport.KEY_ROUTED_ELEMENT_COUNT));
        assertEquals(2000L, stats.get(ChunkStatsSupport.KEY_DURATION_MS));
    }

    /**
     * 空集合与缺失策略/视图：统计只保留能算出来的键。
     *
     * <p>守护 {@code stats}/{@code putStrategyParams} 的缺失分支：策略缺失仍下发片长上限即误导。
     */
    @Test
    void statsShouldOmitStrategyKeysWhenInputsMissing() {
        Map<String, Object> stats = ChunkStatsSupport.stats(null, null, chunkSet(), null, null);

        assertEquals(0, stats.get(ChunkStatsSupport.KEY_CHUNK_COUNT));
        assertEquals(0, stats.get(ChunkStatsSupport.KEY_AVG_CHARS));
        assertEquals(0, stats.get(ChunkStatsSupport.KEY_MAX_CHARS));
        assertEquals(0, stats.get(ChunkStatsSupport.KEY_MULTI_SOURCE_CHUNK_COUNT));
        assertEquals(0, stats.get(ChunkStatsSupport.KEY_AVG_SOURCE_ELEMENT_COUNT));
        assertEquals(0, stats.get(ChunkStatsSupport.KEY_ORPHAN_COUNT));
        assertEquals(0, stats.get(ChunkStatsSupport.KEY_PARENT_COUNT));
        assertFalse(stats.containsKey(ChunkStatsSupport.KEY_TARGET_MAX_LEN));
        assertFalse(stats.containsKey(ChunkStatsSupport.KEY_SKIPPED_ELEMENT_COUNT));
        assertNull(stats.get(ChunkStatsSupport.KEY_DURATION_MS));
    }

    /**
     * 未配置最小合并长度时不统计欠长片。
     *
     * <p>守护 {@code putStrategyParams} 的 {@code minMergeLen > 0} 判定：0 也统计会写出无意义的欠长数。
     */
    @Test
    void statsShouldSkipUnderCountWhenMinMergeLenNotConfigured() {
        ChunkStrategy strategy = strategy();
        strategy.getPipeline().put(PipelineKey.MIN_MERGE_LEN.key(), "0");
        ChunkSet chunkSet = chunkSet(chunk("chunk-0001", ChunkContentType.PARAGRAPH, 5, null, List.of()));

        Map<String, Object> stats = ChunkStatsSupport.stats(null, null, chunkSet, strategy, null);

        assertFalse(stats.containsKey(ChunkStatsSupport.KEY_MIN_MERGE_LEN));
        assertFalse(stats.containsKey(ChunkStatsSupport.KEY_UNDER_MIN_MERGE_COUNT));
    }

    /**
     * 结论陈述：空集合、各项异常、无异常、统计缺失四种形态。
     *
     * <p>守护 {@code chunkSummaryBody}：漏陈述或把正常态说成异常都算错误结论。
     */
    @Test
    void summaryShouldStateEachShape() {
        assertNull(ChunkStatsSupport.summary(null, null, PipelineTaskStatus.SUCCESS.name()));

        assertEquals("没有产出任何切片（无可切内容）", ChunkStatsSupport.summary(null,
                Map.of(ChunkStatsSupport.KEY_CHUNK_COUNT, 0), PipelineTaskStatus.SUCCESS.name()));

        Map<String, Object> normal = Map.of(
                ChunkStatsSupport.KEY_CHUNK_COUNT, 3,
                ChunkStatsSupport.KEY_PARENT_COUNT, 0,
                ChunkStatsSupport.KEY_FALLBACK_COUNT, 0,
                ChunkStatsSupport.KEY_ORPHAN_COUNT, 0,
                ChunkStatsSupport.KEY_OVER_SOFT_MAX_COUNT, 0);
        assertEquals("全部为正文 / 表格 / 图片片，未走兜底",
                ChunkStatsSupport.summary(null, normal, PipelineTaskStatus.PARTIAL_SUCCESS.name()));

        Map<String, Object> abnormal = Map.of(
                ChunkStatsSupport.KEY_CHUNK_COUNT, 9,
                ChunkStatsSupport.KEY_PARENT_COUNT, 2,
                ChunkStatsSupport.KEY_FALLBACK_COUNT, 3,
                ChunkStatsSupport.KEY_ORPHAN_COUNT, 1,
                ChunkStatsSupport.KEY_OVER_SOFT_MAX_COUNT, 4);
        String text = ChunkStatsSupport.summary(null, abnormal, PipelineTaskStatus.SUCCESS.name());
        assertTrue(text.contains("片数含 2 条不产向量的父片"), text);
        assertTrue(text.contains("3 片走了兜底切分"), text);
        assertTrue(text.contains("1 片无同节邻居"), text);
        assertTrue(text.contains("4 片超过软上限"), text);

        // 失败取失败原因；失败原因为空回退环节文案；非成功/部分成功不陈述
        assertEquals("解析超时", ChunkStatsSupport.summary("解析超时", null, PipelineTaskStatus.FAILED.name()));
        assertEquals("切片失败", ChunkStatsSupport.summary(" ", null, PipelineTaskStatus.FAILED.name()));
        assertNull(ChunkStatsSupport.summary(null, normal, PipelineTaskStatus.RUNNING.name()));
    }

    /** 切片夹具 */
    private Chunk chunk(String id, ChunkContentType type, int charCount, String parentId, List<String> sources) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(id);
        chunk.setContentType(type.name());
        chunk.setCharCount(charCount);
        chunk.setParentChunkId(parentId);
        chunk.setSourceElementIds(new ArrayList<>(sources));
        return chunk;
    }

    /** 切片集合夹具 */
    private ChunkSet chunkSet(Chunk... chunks) {
        ChunkSet chunkSet = new ChunkSet();
        chunkSet.setChunks(new ArrayList<>(List.of(chunks)));
        return chunkSet;
    }

    /** 上游视图夹具 */
    private PreprocessView view(ViewElement... elements) {
        PreprocessView view = new PreprocessView();
        view.setElements(new ArrayList<>(List.of(elements)));
        return view;
    }

    /** 视图元素夹具 */
    private ViewElement element(String id, ViewElementStatus status) {
        ViewElement element = new ViewElement();
        element.setElementId(id);
        element.setStatus(status.name());
        return element;
    }

    /** 策略夹具：正文目标 100 / 软上限 200，兜底 50 重叠 5，最小合并长度 30 */
    private ChunkStrategy strategy() {
        ChunkStrategy strategy = new ChunkStrategy();
        ChunkRouteConfig body = new ChunkRouteConfig();
        body.getParams().put("targetMaxLen", "100");
        body.getParams().put("softMaxLen", "200");
        strategy.getRoutes().put(ChunkRoute.BODY.key(), body);
        ChunkRouteConfig fallback = new ChunkRouteConfig();
        fallback.getParams().put("len", "50");
        fallback.getParams().put("overlap", "5");
        strategy.getRoutes().put(ChunkRoute.FALLBACK.key(), fallback);
        strategy.getPipeline().put(PipelineKey.MIN_MERGE_LEN.key(), "30");
        return strategy;
    }
}
