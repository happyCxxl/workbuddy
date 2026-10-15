package com.workbuddy.worker.structure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 组装阈值配置（knowledge.structure 前缀；application.yml 同名 kebab-case 键）。
 * 阈值为草案值，待代表性样本标定。
 *
 * @author cxxl
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.structure")
public class StructureProperties {

    /** 去重 IoU 阈值（草案 0.3） */
    private double iouThreshold = 0.3;

    /** 去重文本相似度阈值（Jaccard，草案 0.7） */
    private double textSimilarityThreshold = 0.7;

    /** XY-cut 行带间距 ÷ 行高（草案 1.5） */
    private double xyCutBandGapRatio = 1.5;

    /** XY-cut 块间距（pt，草案 12） */
    private double xyCutBlockGap = 12.0;

    /** 续表表头一致阈值（草案 0.8） */
    private double continuationHeaderSimilarity = 0.8;

    /** 续表放宽规则列宽容差（pt，草案 3） */
    private double continuationColumnWidthTolerance = 3.0;

    /** 续表放宽规则列宽命中率下限（草案 0.6） */
    private double columnWidthMatchRatio = 0.6;

    /** 标题字号佐证比例：字号 ≥ 文档中位数 × 该值即视为有字号佐证（草案 1.05） */
    private double fontBackedSizeRatio = 1.05;

    /** 字号/加粗标题规则比例：字号 ≥ 文档中位数 × 该值才成标题（草案 1.15） */
    private double fontSignalSizeRatio = 1.15;

    /** 页首判定阈值（pt）：表格 bbox.y 低于该值视为页首 */
    private double pageTopThreshold = 50.0;

    /** 标题候选短句长度上限（防编号误判） */
    private int titleCandidateMaxLength = 40;

    /** 重复页判定：两页文本 bigram Jaccard 阈值（草案值 0.95） */
    private double repeatPageJaccard = 0.95;

    /** 重复段判定：文本相似度阈值（草案值 0.9） */
    private double repeatSegmentSimilarity = 0.9;

    /** 重复段判定：段落最小长度（草案值 50 字符） */
    private int repeatSegmentMinLen = 50;

    /** 噪声页判定：页文本乱码率阈值（草案值 0.4） */
    private double noiseGarbledRatio = 0.4;

    /** 模型判断兜底开关（默认关闭） */
    private boolean modelFallbackEnabled = false;

    /** 溯源偏低阈值（百分比）：可回溯占比低于该值才提告警，与详情页结论文案同口径（80） */
    private int provenanceLowPercent = 80;

    /** 重复判定：按需重建的 bigram 集合缓存条数上限（内存有界；退化场景减少重复重建） */
    private int repeatSetCacheSize = 64;
}
