package com.workbuddy.worker.structure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 组装阈值配置（knowledge.structure 前缀；同名 kebab-case 键在
 * config/knowledge/knowledge-structure.yml）。字段不带代码侧默认值，配置面是唯一基线；
 * 阈值为草案值，待代表性样本标定。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.structure")
public class StructureProperties {

    /** 去重 IoU 阈值 */
    private double iouThreshold;

    /** 去重文本相似度阈值 */
    private double textSimilarityThreshold;

    /** XY-cut 行带间距 ÷ 行高 */
    private double xyCutBandGapRatio;

    /** XY-cut 块间距 */
    private double xyCutBlockGap;

    /** 续表表头一致阈值 */
    private double continuationHeaderSimilarity;

    /** 续表放宽规则列宽容差 */
    private double continuationColumnWidthTolerance;

    /** 续表放宽规则列宽命中率下限 */
    private double columnWidthMatchRatio;

    /** 标题字号佐证比例：字号 ≥ 文档中位数 × 该值即视为有字号佐证 */
    private double fontBackedSizeRatio;

    /** 字号/加粗标题规则比例：字号 ≥ 文档中位数 × 该值才成标题 */
    private double fontSignalSizeRatio;

    /** 页首判定阈值（pt）：表格 bbox.y 低于该值视为页首 */
    private double pageTopThreshold;

    /** 标题候选短句长度上限（防编号误判） */
    private int titleCandidateMaxLength;

    /** 重复页判定：两页文本 bigram Jaccard 阈值 */
    private double repeatPageJaccard;

    /** 重复段判定：文本相似度阈值 */
    private double repeatSegmentSimilarity;

    /** 重复段判定：段落最小长度 */
    private int repeatSegmentMinLen;

    /** 噪声页判定：页文本乱码率阈值 */
    private double noiseGarbledRatio;

    /** 模型判断兜底开关（默认关闭） */
    private boolean modelFallbackEnabled;

    /** 溯源偏低阈值（百分比）：可回溯占比低于该值才提告警，与详情页结论文案同口径 */
    private int provenanceLowPercent;

    /** 重复判定：按需重建的 bigram 集合缓存条数上限（内存有界；退化场景减少重复重建） */
    private int repeatSetCacheSize;
}
