package com.workbuddy.worker.chunking;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 切片参数配置（knowledge.chunk 前缀；application.yml 同名 kebab-case 键）。
 * 参数为草案值，随代表性样本标定。
 *
 * @author cxxl
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.chunk")
public class ChunkProperties {

    /** 目标切片长度上限（字符，草案 800；整段不可拆允许 801–1000） */
    private int targetMaxLen = 800;

    /** 软上限：超过触发递归降级（草案 1000） */
    private int softMaxLen = 1000;

    /** 递归兜底固定长度（草案 500） */
    private int recursiveLen = 500;

    /** 递归兜底重叠（草案 50；仅兜底用，结构切片不用） */
    private int recursiveOverlap = 50;

    /** 最小切片：相邻小段落合并至该长度（对齐开源 Unstructured combine_under_n_chars 口径） */
    private int minMergeLen = 300;

    /** 结构切片重叠：相邻 BODY/TABLE 片前缀式重叠字符数，0=不重叠 */
    private int structureOverlap = 0;

    /** 表格行组阈值：行文本低于该字符数按行组切（草案 30） */
    private int tableRowGroupThreshold = 30;

    /** 表格行组大小：每 N 行一组（草案 3；行级切片 row-slice 用） */
    private int tableRowGroupSize = 3;

    /** 标题路径保留级数（草案 3） */
    private int titlePathMaxLevel = 3;

    /** Token 估算除数（字符数 ÷ 1.5，中文经验值） */
    private double tokenDivisor = 1.5;

    // ---------------- 各算法默认参数（策略参数缺省值来源） ----------------

    /** 标题边界算法：一节文本最大长度，超出走兜底降级 */
    private int titleBoundaryMaxLen = 3000;

    /** 正文固定窗口算法：窗口长度 */
    private int bodyWindowLen = 500;

    /** 正文固定窗口算法：窗口重叠 */
    private int bodyWindowOverlap = 50;

    /** 表格行组切片（row-group）：每片最大行数 */
    private int rowGroupSize = 5;

    /** 表格行组切片（row-group）：每片最大字符数 */
    private int rowGroupMaxLen = 600;

    /** 表格整表切片（whole-table）：整表最大长度，超出降级行级 */
    private int wholeTableMaxLen = 2000;

    /** 表格表+引导段落（context-merged）：前导段截断长度 */
    private int contextLeadMaxLen = 200;
}
