package com.workbuddy.common.enums.chunk;

import com.workbuddy.common.utils.NullUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片参数目录：每个 (算法, 参数键) 一条，携带取值范围与说明。
 * 单一事实源：biz 保存校验的参数范围、前端表单的范围提示、文档的范围表均以此为准；
 * 参数键本身在 {@link com.workbuddy.worker.chunking.strategy.ChunkParamKeys}（JSON 契约常量），
 * 默认值在 {@code ChunkProperties}（application.yml 同名 kebab-case 键），本目录只管"允许填多少、这个键是什么意思"。
 *
 * @author cxxl
 */
public enum ChunkParam {

    // ---------------- 正文 · 聚合三算法（段落 / 结构混合 / 句子） ----------------

    /** 段落/结构/句子聚合：目标片长上限 */
    AGGREGATE_TARGET_MAX_LEN(ChunkAlgorithm.BODY_PARAGRAPH_AGGREGATE, "targetMaxLen", 100, 5000,
            "目标片长上限：累计到该长度即结算成片"),

    /** 段落/结构/句子聚合：软上限 */
    AGGREGATE_SOFT_MAX_LEN(ChunkAlgorithm.BODY_PARAGRAPH_AGGREGATE, "softMaxLen", 100, 8000,
            "软上限：单元素超过它先结算缓冲、再走兜底降级"),

    STRUCTURE_HYBRID_TARGET_MAX_LEN(ChunkAlgorithm.BODY_STRUCTURE_HYBRID, "targetMaxLen", 100, 5000,
            "目标片长上限：累计到该长度即结算成片"),

    STRUCTURE_HYBRID_SOFT_MAX_LEN(ChunkAlgorithm.BODY_STRUCTURE_HYBRID, "softMaxLen", 100, 8000,
            "软上限：单元素超过它先结算缓冲、再走兜底降级"),

    SENTENCE_AGGREGATE_TARGET_MAX_LEN(ChunkAlgorithm.BODY_SENTENCE_AGGREGATE, "targetMaxLen", 100, 5000,
            "目标片长上限：累计到该长度即结算成片"),

    SENTENCE_AGGREGATE_SOFT_MAX_LEN(ChunkAlgorithm.BODY_SENTENCE_AGGREGATE, "softMaxLen", 100, 8000,
            "软上限：跨元素累计超过它即结算成片"),

    // ---------------- 正文 · 标题边界 / 固定窗口 ----------------

    /** 标题边界：一节文本最大长度 */
    TITLE_BOUNDARY_MAX_LEN(ChunkAlgorithm.BODY_TITLE_BOUNDARY, "maxLen", 100, 20000,
            "片长上限：一节文本超过它走兜底降级成多片"),

    BODY_WINDOW_LEN(ChunkAlgorithm.BODY_FIXED_WINDOW, "len", 100, 5000, "窗口长度（字符）"),

    BODY_WINDOW_OVERLAP(ChunkAlgorithm.BODY_FIXED_WINDOW, "overlap", 0, 1000, "相邻窗口重叠（字符）"),

    // ---------------- 表格路 ----------------

    ROW_SLICE_GROUP_THRESHOLD(ChunkAlgorithm.TABLE_ROW_SLICE, "groupThreshold", 5, 500,
            "短行阈值：行文本短于该字符数按行组切"),

    ROW_SLICE_GROUP_SIZE(ChunkAlgorithm.TABLE_ROW_SLICE, "groupSize", 1, 20, "短行分组：每 N 行一组"),

    ROW_GROUP_GROUP_SIZE(ChunkAlgorithm.TABLE_ROW_GROUP, "groupSize", 1, 50, "每片最大行数"),

    ROW_GROUP_MAX_LEN(ChunkAlgorithm.TABLE_ROW_GROUP, "maxLen", 100, 5000, "每片最大字符数"),

    WHOLE_TABLE_MAX_LEN(ChunkAlgorithm.TABLE_WHOLE, "maxLen", 100, 20000,
            "整表最大长度：超过它降级为行级切片"),

    CONTEXT_MERGED_LEAD_MAX_LEN(ChunkAlgorithm.TABLE_CONTEXT_MERGED, "leadMaxLen", 0, 1000,
            "引导段落截断长度（0 = 不截断）"),

    CONTEXT_MERGED_GROUP_THRESHOLD(ChunkAlgorithm.TABLE_CONTEXT_MERGED, "groupThreshold", 5, 500,
            "短行阈值：行文本短于该字符数按行组切"),

    CONTEXT_MERGED_GROUP_SIZE(ChunkAlgorithm.TABLE_CONTEXT_MERGED, "groupSize", 1, 20, "短行分组：每 N 行一组"),

    // ---------------- 兜底路 ----------------

    FALLBACK_RECURSIVE_LEN(ChunkAlgorithm.FALLBACK_RECURSIVE, "len", 100, 5000, "硬切窗口长度（字符）"),

    FALLBACK_RECURSIVE_OVERLAP(ChunkAlgorithm.FALLBACK_RECURSIVE, "overlap", 0, 1000, "相邻片重叠（字符）"),

    FALLBACK_WINDOW_LEN(ChunkAlgorithm.FALLBACK_FIXED_WINDOW, "len", 100, 5000, "硬切窗口长度（字符）"),

    FALLBACK_WINDOW_OVERLAP(ChunkAlgorithm.FALLBACK_FIXED_WINDOW, "overlap", 0, 1000, "相邻片重叠（字符）");

    private final ChunkAlgorithm algorithm;
    private final String key;
    private final int min;
    private final int max;
    private final String desc;

    ChunkParam(ChunkAlgorithm algorithm, String key, int min, int max, String desc) {
        this.algorithm = algorithm;
        this.key = key;
        this.min = min;
        this.max = max;
        this.desc = desc;
    }

    /** 归属算法 */
    public ChunkAlgorithm algorithm() {
        return algorithm;
    }

    /** 策略快照里的参数键（JSON 契约） */
    public String key() {
        return key;
    }

    /** 允许的最小值 */
    public int min() {
        return min;
    }

    /** 允许的最大值 */
    public int max() {
        return max;
    }

    /** 参数说明（人类可读） */
    public String desc() {
        return desc;
    }

    /** 按 (算法, 参数键) 精确查找；未识别返回 null（未知参数按前向兼容透传） */
    public static ChunkParam of(ChunkAlgorithm algorithm, String key) {
        if (NullUtil.isNull(algorithm) || NullUtil.isNull(key) || key.isEmpty()) {
            return null;
        }
        for (ChunkParam param : values()) {
            if (param.algorithm == algorithm && param.key.equals(key)) {
                return param;
            }
        }
        return null;
    }

    /** 某个算法允许的全部参数（保存校验与前端范围提示共用） */
    public static List<ChunkParam> ofAlgorithm(ChunkAlgorithm algorithm) {
        List<ChunkParam> params = new ArrayList<>();
        if (NullUtil.isNull(algorithm)) {
            return params;
        }
        for (ChunkParam param : values()) {
            if (param.algorithm == algorithm) {
                params.add(param);
            }
        }
        return params;
    }
}
