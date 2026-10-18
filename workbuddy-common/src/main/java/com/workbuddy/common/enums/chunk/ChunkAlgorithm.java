package com.workbuddy.common.enums.chunk;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.utils.NullUtil;

/**
 * 切片算法目录：每个 (路由, 算法键) 一个常量，携带序列化键、当前支持状态与正文 flush 规则。
 * 单一事实源：biz 保存校验、前端置灰、注册表索引、默认算法与管线 flush 矩阵均以此为准。
 * 上线新算法 = ① 新增常量（supported=false）② 实现切片器并声明本常量 ③ 改 supported=true（前端随之解除置灰）。
 */
public enum ChunkAlgorithm {

    // ---------------- 正文路 ----------------

    /** 段落聚合（默认）：相邻段落累计达到目标片长即结算成片；表格/图片边界断片 */
    BODY_PARAGRAPH_AGGREGATE(ChunkRoute.BODY, "paragraph-aggregate", true, true),
    /** 标题边界：一节文本一片（表格/图片不断片）；一节合计超过片长上限时走兜底降级成多片 */
    BODY_TITLE_BOUNDARY(ChunkRoute.BODY, "title-boundary", true, false),
    /** 结构混合：标题边界 + 段落聚合混合（累计达到目标片长即结算）；表格/图片不断片 */
    BODY_STRUCTURE_HYBRID(ChunkRoute.BODY, "structure-hybrid", true, false),
    /** 句子聚合：跨元素按句聚合，累计达到目标片长即结算；超长句走兜底降级 */
    BODY_SENTENCE_AGGREGATE(ChunkRoute.BODY, "sentence-aggregate", true, true),
    /** 固定长度+重叠：缓冲到边界后按窗口长度切，相邻窗口带重叠；不标兜底降级 */
    BODY_FIXED_WINDOW(ChunkRoute.BODY, "fixed-window", true, true),
    /** 语义切片（预留）：按语义相似度断点切片，依赖 embedding 能力，暂未上线 */
    BODY_SEMANTIC(ChunkRoute.BODY, "semantic", false, false),

    // ---------------- 表格路 ----------------

    /** 行级切片+表头随片（默认）：单元格文本短的「短行」按行组聚合，每片带表头 */
    TABLE_ROW_SLICE(ChunkRoute.TABLE, "row-slice", true, false),
    /** 行组切片：数据行按行数与字符长度双上限结算成组，每片带表头 */
    TABLE_ROW_GROUP(ChunkRoute.TABLE, "row-group", true, false),
    /** 整表一片：整张表一个 Markdown 片；超过片长上限时降级为行级切片 */
    TABLE_WHOLE(ChunkRoute.TABLE, "whole-table", true, false),
    /** 表+引导段落：行级切片的基础上，每片前缀拼接该表前的引导段落（截断到上限）；与「表格并入正文流」互斥 */
    TABLE_CONTEXT_MERGED(ChunkRoute.TABLE, "context-merged", true, false),

    // ---------------- 图片路 ----------------

    /** 图注占位（默认）：图片以图注文本成片（无图注则占位文本），不解析图片内容 */
    IMAGE_CAPTION_PLACEHOLDER(ChunkRoute.IMAGE, "caption-placeholder", true, false),
    /** 图注+上下文文本（预留）：图注拼接邻近正文作为图片片，暂未上线 */
    IMAGE_CAPTION_CONTEXT(ChunkRoute.IMAGE, "caption-context", false, false),
    /** OCR 文字（预留）：识别图片内文字成片，依赖外部 OCR 能力，暂未上线 */
    IMAGE_OCR(ChunkRoute.IMAGE, "ocr", false, false),
    /** 视觉摘要（预留）：生成图片内容摘要成片，依赖多模态能力，暂未上线 */
    IMAGE_VISUAL_SUMMARY(ChunkRoute.IMAGE, "visual-summary", false, false),
    /** 多模态向量（预留）：图片直接多模态向量化，依赖多模态能力，暂未上线 */
    IMAGE_MULTIMODAL(ChunkRoute.IMAGE, "multimodal", false, false),

    // ---------------- 兜底 ----------------

    /** 递归降级（默认）：先按句边界切，超长句再按固定长度硬切（带重叠） */
    FALLBACK_RECURSIVE(ChunkRoute.FALLBACK, "recursive-length", true, false),
    /** 固定长度+重叠：纯窗口硬切（不看句边界），相邻片带重叠 */
    FALLBACK_FIXED_WINDOW(ChunkRoute.FALLBACK, "fixed-window", true, false),
    /** 语义断点降级（预留）：按语义断点切分超长文本，依赖 embedding 能力，暂未上线 */
    FALLBACK_SEMANTIC(ChunkRoute.FALLBACK, "semantic-boundary", false, false),
    /** 不兜底：超长文本原样输出单片 */
    FALLBACK_NONE(ChunkRoute.FALLBACK, "none", true, false);

    private final ChunkRoute route;
    private final String key;
    private final boolean supported;
    /** 正文聚合是否在表格/图片边界结算（仅正文路语义，其余路恒 false） */
    private final boolean flushOnContentBoundary;

    ChunkAlgorithm(ChunkRoute route, String key, boolean supported, boolean flushOnContentBoundary) {
        this.route = route;
        this.key = key;
        this.supported = supported;
        this.flushOnContentBoundary = flushOnContentBoundary;
    }

    /** 所属路由。 */
    public ChunkRoute route() {
        return route;
    }

    /** 策略快照中的序列化键（JSON 契约）。 */
    public String key() {
        return key;
    }

    /** 是否在当前支持集（保存校验 + 前端置灰口径）。 */
    public boolean supported() {
        return supported;
    }

    /** 正文聚合是否在表格/图片边界结算。 */
    public boolean flushOnContentBoundary() {
        return flushOnContentBoundary;
    }

    /** 按 (路由, 算法键) 精确查找；未识别返回 null。 */
    public static ChunkAlgorithm of(ChunkRoute route, String key) {
        if (NullUtil.isNull(route) || StrUtil.isBlank(key)) {
            return null;
        }
        for (ChunkAlgorithm algorithm : values()) {
            if (algorithm.route == route && algorithm.key.equals(key)) {
                return algorithm;
            }
        }
        return null;
    }
}
