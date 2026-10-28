package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.dto.response.chunk.ChunkStatsVO;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.biz.service.support.stats.ChunkStatsSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        ChunkStatsVO stats = ChunkStatsSupport.stats(null, null, chunkSet, strategy(), null);

        assertEquals(6, stats.getChunkCount());
        assertEquals(2, stats.getSectionCount());
        assertEquals(1, stats.getParagraphCount());
        assertEquals(1, stats.getTableCount());
        assertEquals(1, stats.getImageCount());
        assertEquals(1, stats.getFallbackCount());
        assertEquals(1, stats.getParentCount());
        // 孤儿 = 无父片且自身不是父片：表格 / 图片 / 兜底 三片
        assertEquals(3, stats.getOrphanCount());
        assertEquals(945, stats.getTotalChars());
        assertEquals(158, stats.getAvgChars());
        assertEquals(500, stats.getMaxChars());
        // 目标上限 100：正好 100 的片不算超；软上限 200：500 与 300 超
        assertEquals(100, stats.getTargetMaxLen());
        assertEquals(200, stats.getSoftMaxLen());
        assertEquals(2, stats.getOverTargetMaxCount());
        assertEquals(2, stats.getOverSoftMaxCount());
        // 欠长 30：正好 30 的片不算欠；父片（SECTION）不参与欠长统计
        assertEquals(30, stats.getMinMergeLen());
        assertEquals(1, stats.getUnderMinMergeCount());
        assertEquals(50, stats.getFallbackLen());
        assertEquals(5, stats.getFallbackOverlap());
        // 来源元素：多来源 2 片（2 个与 3 个来源），平均 (1+2+0+1+3+0)/6 ≈ 1.17 → 1
        assertEquals(2, stats.getMultiSourceChunkCount());
        assertEquals(1, stats.getAvgSourceElementCount());
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

        ChunkStatsVO stats = ChunkStatsSupport.stats(started, finished, chunkSet, strategy(), view);

        assertEquals(2, stats.getSkippedElementCount());
        assertEquals(2, stats.getRoutedElementCount());
        assertEquals(2000L, stats.getDurationMs());
    }

    /**
     * 空集合与缺失策略/视图：统计只保留能算出来的键。
     *
     * <p>守护 {@code stats}/{@code putStrategyParams} 的缺失分支：策略缺失仍下发片长上限即误导。
     */
    @Test
    void statsShouldOmitStrategyKeysWhenInputsMissing() {
        ChunkStatsVO stats = ChunkStatsSupport.stats(null, null, chunkSet(), null, null);

        assertEquals(0, stats.getChunkCount());
        assertEquals(0, stats.getAvgChars());
        assertEquals(0, stats.getMaxChars());
        assertEquals(0, stats.getMultiSourceChunkCount());
        assertEquals(0, stats.getAvgSourceElementCount());
        assertEquals(0, stats.getOrphanCount());
        assertEquals(0, stats.getParentCount());
        assertNull(stats.getTargetMaxLen());
        assertNull(stats.getSkippedElementCount());
        assertNull(stats.getDurationMs());
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

        ChunkStatsVO stats = ChunkStatsSupport.stats(null, null, chunkSet, strategy, null);

        assertNull(stats.getMinMergeLen());
        assertNull(stats.getUnderMinMergeCount());
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
                emptyStats(), PipelineTaskStatus.SUCCESS.name()));

        ChunkStatsVO normal = new ChunkStatsVO();
        normal.setChunkCount(3);
        normal.setParentCount(0);
        normal.setFallbackCount(0);
        normal.setOrphanCount(0);
        normal.setOverSoftMaxCount(0);
        assertEquals("全部为正文 / 表格 / 图片片，未走兜底",
                ChunkStatsSupport.summary(null, normal, PipelineTaskStatus.PARTIAL_SUCCESS.name()));

        ChunkStatsVO abnormal = new ChunkStatsVO();
        abnormal.setChunkCount(9);
        abnormal.setParentCount(2);
        abnormal.setFallbackCount(3);
        abnormal.setOrphanCount(1);
        abnormal.setOverSoftMaxCount(4);
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

    /** 空统计（无任何计数，等价于「没有产出切片」） */
    private static ChunkStatsVO emptyStats() {
        ChunkStatsVO stats = new ChunkStatsVO();
        stats.setChunkCount(0);
        return stats;
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

    /**
     * 上限为 0（未配置）时超限片数记 0，且不参与欠长统计。
     *
     * <p>守护 {@code countOver}/{@code countUnder} 的 {@code limit <= 0} 短路：短路改小会把"未配置"当成 0
     * 上限，所有非空切片都被算成超限。
     */
    @Test
    void statsShouldTreatNonPositiveLimitsAsUnconfigured() {
        ChunkStrategy zero = new ChunkStrategy();
        ChunkRouteConfig body = new ChunkRouteConfig();
        body.getParams().put("targetMaxLen", "0");
        body.getParams().put("softMaxLen", "0");
        zero.getRoutes().put(ChunkRoute.BODY.key(), body);
        zero.getPipeline().put(PipelineKey.MIN_MERGE_LEN.key(), "0");
        ChunkSet chunkSet = chunkSet(
                chunk("chunk-0001", ChunkContentType.PARAGRAPH, 50, null, List.of("e1")),
                chunk("chunk-0002", ChunkContentType.TABLE, 0, null, List.of("e1")));

        ChunkStatsVO stats = ChunkStatsSupport.stats(null, null, chunkSet, zero, null);

        assertEquals(0, stats.getOverTargetMaxCount());
        assertEquals(0, stats.getOverSoftMaxCount());
    }

    /**
     * 欠长统计排除父片（整章聚合不算欠长）：只数非 SECTION 的短片。
     *
     * <p>守护 {@code countUnder} 的父片过滤：过滤条件写反会把父片算成欠长、把正文短片漏掉。
     */
    @Test
    void statsShouldExcludeSectionFromUnderMinMerge() {
        ChunkSet chunkSet = chunkSet(
                chunk("s-1", ChunkContentType.SECTION, 5, null, List.of("e1")),
                chunk("s-2", ChunkContentType.SECTION, 6, null, List.of("e2")),
                chunk("p-1", ChunkContentType.PARAGRAPH, 10, "s-1", List.of("e1")),
                chunk("p-2", ChunkContentType.PARAGRAPH, 20, "s-1", List.of("e2")),
                chunk("p-3", ChunkContentType.PARAGRAPH, 25, "s-2", List.of("e3")));

        ChunkStatsVO stats = ChunkStatsSupport.stats(null, null, chunkSet, strategy(), null);

        // 三个正文短片（10/20/25）都低于 minMergeLen=30；两个更短的父片不参与欠长统计
        assertEquals(3, stats.getUnderMinMergeCount());
    }
}
