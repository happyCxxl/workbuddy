package com.workbuddy.biz.service.support;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.rules.PreprocessViewRules;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切片产物统计汇总：读切片集合产物 → 片数/类型分布/片长/兜底/父子/来源计数 → 摘要文案。
 *
 * <p>纯读取，不落库、不改产物；执行树的切片节点与切片详情共用同一份口径。
 * 键值含义见本类的 {@code KEY_*} 常量。
 *
 * @author cxxl
 */
public final class ChunkStatsSupport {

    /** 切片总数（含父片；父片不产向量，片数会比检索条数多） */
    public static final String KEY_CHUNK_COUNT = "chunkCount";

    /** 父片数（contentType=SECTION） */
    public static final String KEY_SECTION_COUNT = "sectionCount";

    /** 正文片数 */
    public static final String KEY_PARAGRAPH_COUNT = "paragraphCount";

    /** 表格片数 */
    public static final String KEY_TABLE_COUNT = "tableCount";

    /** 图片片数 */
    public static final String KEY_IMAGE_COUNT = "imageCount";

    /** 兜底片数（超长降级切分） */
    public static final String KEY_FALLBACK_COUNT = "fallbackCount";

    /** 被挂子片的父片数（按子片的 parentChunkId 反查） */
    public static final String KEY_PARENT_COUNT = "parentCount";

    /** 孤儿片数（无父片、也无同节邻居） */
    public static final String KEY_ORPHAN_COUNT = "orphanCount";

    /** 总字符数（全部片 content 长度合计） */
    public static final String KEY_TOTAL_CHARS = "totalChars";

    /** 平均片长（四舍五入取整） */
    public static final String KEY_AVG_CHARS = "avgChars";

    /** 最长片（字符数） */
    public static final String KEY_MAX_CHARS = "maxChars";

    /** 超过目标片长上限（targetMaxLen）的片数 */
    public static final String KEY_OVER_TARGET_MAX_COUNT = "overTargetMaxCount";

    /** 超过软上限（softMaxLen）的片数：这些片只能靠兜底降级切开 */
    public static final String KEY_OVER_SOFT_MAX_COUNT = "overSoftMaxCount";

    /** 最小合并长度（流程层 minMergeLen，默认 300） */
    public static final String KEY_MIN_MERGE_LEN = "minMergeLen";

    /** 欠长片数（子片里短于 minMergeLen 的片：合并后仍偏短，多为合并尾巴） */
    public static final String KEY_UNDER_MIN_MERGE_COUNT = "underMinMergeCount";

    /** 进入切片的元素数（上游视图元素 − 跳过） */
    public static final String KEY_ROUTED_ELEMENT_COUNT = "routedElementCount";

    /** 跳过元素数（预处理剔除态与重复份，不进切片） */
    public static final String KEY_SKIPPED_ELEMENT_COUNT = "skippedElementCount";

    /** 来源元素多于 1 个的片数 */
    public static final String KEY_MULTI_SOURCE_CHUNK_COUNT = "multiSourceChunkCount";

    /** 平均来源元素数（四舍五入取整） */
    public static final String KEY_AVG_SOURCE_ELEMENT_COUNT = "avgSourceElementCount";

    /** 正文切片的目标片长上限（策略参数） */
    public static final String KEY_TARGET_MAX_LEN = "targetMaxLen";

    /** 正文切片的软上限（策略参数） */
    public static final String KEY_SOFT_MAX_LEN = "softMaxLen";

    /** 兜底切分的单片长度（策略参数） */
    public static final String KEY_FALLBACK_LEN = "fallbackLen";

    /** 兜底切分的重叠字符数（策略参数） */
    public static final String KEY_FALLBACK_OVERLAP = "fallbackOverlap";

    /** 本次运行耗时（毫秒） */
    public static final String KEY_DURATION_MS = "durationMs";

    /** 正文路由的目标片长参数键 */
    private static final String PARAM_TARGET_MAX_LEN = "targetMaxLen";

    /** 正文路由的软上限参数键 */
    private static final String PARAM_SOFT_MAX_LEN = "softMaxLen";

    /** 兜底路由的单片长度参数键 */
    private static final String PARAM_FALLBACK_LEN = "len";

    /** 兜底路由的重叠参数键 */
    private static final String PARAM_FALLBACK_OVERLAP = "overlap";

    private ChunkStatsSupport() {
    }

    /**
     * 读切片集合产物；对象位置为空、对象读不到或 JSON 解析失败都返回 null。
     *
     * @param fileStorage 对象存储
     * @param ref         产物对象位置（含存储类型与桶名），可空
     * @return 切片集合；取不到返回 null
     */
    public static ChunkSet readChunkSet(FileStorage fileStorage, ObjectRef ref) {
        return StatsSupport.readArtifact(fileStorage, ref, ChunkSet.class);
    }

    /**
     * 切片集合 + 策略 + 上游视图 → 统计。键值含义见本类的 {@code KEY_*} 常量。
     *
     * @param startedAt    运行开始时间（提供耗时），可空
     * @param finishedAt   运行结束时间（提供耗时），可空
     * @param chunkSet     该次运行的切片集合产物，可空
     * @param strategy     该次运行的切片策略快照（解析后参数已补全默认），可空
     * @param upstreamView 上游预处理视图（提供"进入切片 / 跳过"元素数），可空
     * @return 统计；产物为空时返回 null（不陈述结论）
     */
    public static Map<String, Object> stats(LocalDateTime startedAt, LocalDateTime finishedAt, ChunkSet chunkSet,
                                            ChunkStrategy strategy, PreprocessView upstreamView) {
        if (NullUtil.isNull(chunkSet)) {
            return null;
        }
        List<Chunk> chunks = ObjectUtil.defaultIfNull(chunkSet.getChunks(), List.of());
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put(KEY_CHUNK_COUNT, chunks.size());
        putTypeCounts(stats, chunks);
        putLengthStats(stats, chunks);
        putRoutingStats(stats, upstreamView);
        putStrategyParams(stats, chunks, strategy);
        stats.put(KEY_MULTI_SOURCE_CHUNK_COUNT, countMultiSource(chunks));
        stats.put(KEY_AVG_SOURCE_ELEMENT_COUNT, avgSourceCount(chunks));
        stats.put(KEY_DURATION_MS, StatsSupport.durationMs(startedAt, finishedAt));
        return stats;
    }

    /**
     * 切片结论文案：按统计陈述"片数里有多少不产向量的父片、多少片走了兜底"等异常项，正常项不陈述；
     * 空集合给原因；失败取失败原因；统计缺失（产物不可读）时返回 null。
     *
     * @param errorMsg 失败原因
     * @param stats    切片统计（**可空**：产物不可读时为 null）
     * @param status   任务状态
     * @return 结论文案；统计缺失或无可陈述内容时返回 null
     */
    public static String summary(String errorMsg, Map<String, Object> stats, String status) {
        return StatsSupport.summaryOf(errorMsg, status, "切片失败", () -> chunkSummaryBody(stats));
    }

    /** 集合统计陈述：统计缺失时不陈述结论；空集合与各异常形态逐条陈述 */
    private static String chunkSummaryBody(Map<String, Object> stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        if (intOf(stats.get(KEY_CHUNK_COUNT)) == 0) {
            return "没有产出任何切片（无可切内容）";
        }
        List<String> notes = new ArrayList<>();
        int parents = intOf(stats.get(KEY_PARENT_COUNT));
        if (parents > 0) {
            notes.add("片数含 " + parents + " 条不产向量的父片");
        }
        int fallback = intOf(stats.get(KEY_FALLBACK_COUNT));
        if (fallback > 0) {
            notes.add(fallback + " 片走了兜底切分");
        }
        int orphans = intOf(stats.get(KEY_ORPHAN_COUNT));
        if (orphans > 0) {
            notes.add(orphans + " 片无同节邻居");
        }
        int overSoft = intOf(stats.get(KEY_OVER_SOFT_MAX_COUNT));
        if (overSoft > 0) {
            notes.add(overSoft + " 片超过软上限");
        }
        if (notes.isEmpty()) {
            return "全部为正文 / 表格 / 图片片，未走兜底";
        }
        return String.join("；", notes);
    }

    /** 按内容类型计数（父片单列，其余按四路切片产物） */
    private static void putTypeCounts(Map<String, Object> stats, List<Chunk> chunks) {
        int section = 0;
        int paragraph = 0;
        int table = 0;
        int image = 0;
        int fallback = 0;
        for (Chunk chunk : chunks) {
            String type = ObjectUtil.defaultIfNull(chunk.getContentType(), "");
            switch (type) {
                case "SECTION" -> section++;
                case "PARAGRAPH" -> paragraph++;
                case "TABLE" -> table++;
                case "IMAGE" -> image++;
                case "FALLBACK" -> fallback++;
                default -> {
                }
            }
        }
        stats.put(KEY_SECTION_COUNT, section);
        stats.put(KEY_PARAGRAPH_COUNT, paragraph);
        stats.put(KEY_TABLE_COUNT, table);
        stats.put(KEY_IMAGE_COUNT, image);
        stats.put(KEY_FALLBACK_COUNT, fallback);
        stats.put(KEY_PARENT_COUNT, countParents(chunks));
        stats.put(KEY_ORPHAN_COUNT, countOrphans(chunks));
    }

    /** 片长统计：总量 / 均值 / 最长片 / 超目标上限与超软上限的片数 */
    private static void putLengthStats(Map<String, Object> stats, List<Chunk> chunks) {
        int totalChars = chunks.stream().mapToInt(Chunk::getCharCount).sum();
        int maxChars = chunks.stream().mapToInt(Chunk::getCharCount).max().orElse(0);
        stats.put(KEY_TOTAL_CHARS, totalChars);
        stats.put(KEY_AVG_CHARS, chunks.isEmpty() ? 0 : Math.round((float) totalChars / chunks.size()));
        stats.put(KEY_MAX_CHARS, maxChars);
    }

    /** 进入切片 / 跳过元素数：口径取上游视图（跳过 = 预处理剔除态与重复份） */
    private static void putRoutingStats(Map<String, Object> stats, PreprocessView upstreamView) {
        if (NullUtil.isNull(upstreamView)) {
            return;
        }
        List<ViewElement> elements = ObjectUtil.defaultIfNull(upstreamView.getElements(), List.of());
        int skipped = PreprocessStatsSupport.countStatuses(elements, PreprocessViewRules.CHUNK_SKIP_STATUSES);
        stats.put(KEY_SKIPPED_ELEMENT_COUNT, skipped);
        stats.put(KEY_ROUTED_ELEMENT_COUNT, elements.size() - skipped);
    }

    /** 策略参数（片长上限与兜底切分参数）：策略不可解析时不下发这几个口径 */
    private static void putStrategyParams(Map<String, Object> stats, List<Chunk> chunks, ChunkStrategy strategy) {
        if (NullUtil.isNull(strategy)) {
            return;
        }
        ChunkRouteConfig body = strategy.route(ChunkRoute.BODY);
        if (NullUtil.isNotNull(body)) {
            int targetMaxLen = body.intParam(PARAM_TARGET_MAX_LEN, 0);
            int softMaxLen = body.intParam(PARAM_SOFT_MAX_LEN, 0);
            stats.put(KEY_TARGET_MAX_LEN, targetMaxLen);
            stats.put(KEY_SOFT_MAX_LEN, softMaxLen);
            stats.put(KEY_OVER_TARGET_MAX_COUNT, countOver(chunks, targetMaxLen));
            stats.put(KEY_OVER_SOFT_MAX_COUNT, countOver(chunks, softMaxLen));
        }
        ChunkRouteConfig fallback = strategy.route(ChunkRoute.FALLBACK);
        if (NullUtil.isNotNull(fallback)) {
            stats.put(KEY_FALLBACK_LEN, fallback.intParam(PARAM_FALLBACK_LEN, 0));
            stats.put(KEY_FALLBACK_OVERLAP, fallback.intParam(PARAM_FALLBACK_OVERLAP, 0));
        }
        int minMergeLen = strategy.pipelineInt(PipelineKey.MIN_MERGE_LEN, 0);
        if (minMergeLen > 0) {
            stats.put(KEY_MIN_MERGE_LEN, minMergeLen);
            stats.put(KEY_UNDER_MIN_MERGE_COUNT, countUnder(chunks, minMergeLen));
        }
    }

    /** 超过给定片长的片数 */
    private static int countOver(List<Chunk> chunks, int limit) {
        if (limit <= 0) {
            return 0;
        }
        return (int) chunks.stream().filter(chunk -> chunk.getCharCount() > limit).count();
    }

    /** 短于给定长度的子片数（父片是整章聚合，不参与欠长统计） */
    private static int countUnder(List<Chunk> chunks, int limit) {
        if (limit <= 0) {
            return 0;
        }
        return (int) chunks.stream()
                .filter(chunk -> !ChunkContentType.SECTION.name().equals(chunk.getContentType()))
                .filter(chunk -> chunk.getCharCount() < limit)
                .count();
    }

    /** 被挂子片的父片数：子片的 parentChunkId 落在集合内的片 ID 上 */
    private static int countParents(List<Chunk> chunks) {
        Set<String> ids = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        for (Chunk chunk : chunks) {
            ids.add(chunk.getChunkId());
        }
        for (Chunk chunk : chunks) {
            if (StrUtil.isNotBlank(chunk.getParentChunkId())) {
                referenced.add(chunk.getParentChunkId());
            }
        }
        referenced.retainAll(ids);
        return referenced.size();
    }

    /** 孤儿片数：既不是父片、也没有父片（无同节邻居） */
    private static int countOrphans(List<Chunk> chunks) {
        int orphans = 0;
        for (Chunk chunk : chunks) {
            if (StrUtil.isBlank(chunk.getParentChunkId())
                    && !ChunkContentType.SECTION.name().equals(chunk.getContentType())) {
                orphans++;
            }
        }
        return orphans;
    }

    /** 来源元素多于 1 个的片数 */
    private static int countMultiSource(List<Chunk> chunks) {
        return (int) chunks.stream()
                .filter(chunk -> ObjectUtil.defaultIfNull(chunk.getSourceElementIds(), List.of()).size() > 1)
                .count();
    }

    /** 平均来源元素数（四舍五入取整；无片记 0） */
    private static int avgSourceCount(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }
        int total = chunks.stream()
                .mapToInt(chunk -> ObjectUtil.defaultIfNull(chunk.getSourceElementIds(), List.of()).size())
                .sum();
        return Math.round((float) total / chunks.size());
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
