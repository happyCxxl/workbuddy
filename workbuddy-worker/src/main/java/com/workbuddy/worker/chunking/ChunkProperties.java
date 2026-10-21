package com.workbuddy.worker.chunking;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 切片参数配置（knowledge.chunk 前缀；同名 kebab-case 键在 config/knowledge/knowledge-chunk.yml）。
 * 字段不带代码侧默认值，配置面是唯一基线；参数为草案值，随代表性样本标定。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.chunk")
public class ChunkProperties {

    /** 目标切片长度上限（字符）；整段不可拆时允许超出 */
    private int targetMaxLen;

    /** 软上限：超过触发递归降级 */
    private int softMaxLen;

    /** 递归兜底固定长度 */
    private int recursiveLen;

    /** 递归兜底重叠 */
    private int recursiveOverlap;

    /** 最小切片：相邻小段落合并至该长度（对齐开源 Unstructured combine_under_n_chars 口径） */
    private int minMergeLen;

    /** 结构切片重叠：相邻 BODY/TABLE 片前缀式重叠字符数，0=不重叠 */
    private int structureOverlap;

    /** 表格行组阈值：行文本低于该字符数按行组切 */
    private int tableRowGroupThreshold;

    /** 表格行组大小：每 N 行一组 */
    private int tableRowGroupSize;

    /** 标题路径保留级数 */
    private int titlePathMaxLevel;

    /** Token 估算除数（字符数 ÷ 该值，中文经验值） */
    private double tokenDivisor;

    // ---------------- 各算法默认参数（策略参数缺省值来源） ----------------

    /** 标题边界算法：一节文本最大长度，超出走兜底降级 */
    private int titleBoundaryMaxLen;

    /** 正文固定窗口算法：窗口长度 */
    private int bodyWindowLen;

    /** 正文固定窗口算法：窗口重叠 */
    private int bodyWindowOverlap;

    /** 表格行组切片（row-group）：每片最大行数 */
    private int rowGroupSize;

    /** 表格行组切片（row-group）：每片最大字符数 */
    private int rowGroupMaxLen;

    /** 表格整表切片（whole-table）：整表最大长度，超出降级行级 */
    private int wholeTableMaxLen;

    /** 表格表+引导段落（context-merged）：前导段截断长度 */
    private int contextLeadMaxLen;
}
