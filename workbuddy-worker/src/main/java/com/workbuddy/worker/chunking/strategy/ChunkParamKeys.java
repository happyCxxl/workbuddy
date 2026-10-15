package com.workbuddy.worker.chunking.strategy;

/**
 * 切片路由参数键（JSON 契约）：routes 各算法参数的序列化键。
 * 只引用不改值；默认值单一事实源在 ChunkProperties。
 *
 * @author cxxl
 */
public final class ChunkParamKeys {

    /** 段落/句子聚合软上限（字符） */
    public static final String SOFT_MAX_LEN = "softMaxLen";

    /** 段落/句子聚合目标上限（字符） */
    public static final String TARGET_MAX_LEN = "targetMaxLen";

    /** 固定窗口长度（字符） */
    public static final String LEN = "len";

    /** 固定窗口重叠（字符） */
    public static final String OVERLAP = "overlap";

    /** 标题边界/表格单策略最大长度（字符） */
    public static final String MAX_LEN = "maxLen";

    /** 表格表+引导段落：前导段截断长度（字符） */
    public static final String LEAD_MAX_LEN = "leadMaxLen";

    /** 表格行分组阈值（字符） */
    public static final String GROUP_THRESHOLD = "groupThreshold";

    /** 表格行组大小（行数） */
    public static final String GROUP_SIZE = "groupSize";

    private ChunkParamKeys() {
    }
}
