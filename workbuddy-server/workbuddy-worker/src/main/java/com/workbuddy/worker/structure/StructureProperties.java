package com.workbuddy.worker.structure;

import com.workbuddy.worker.structure.craft.HeaderFooterDetector;
import jakarta.annotation.PostConstruct;
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

    /** 续表标记：上一页表格贴页底时，两表列 x 区间重合比例下限 */
    private double continuationOverlapRatio;

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

    /** 页眉页脚判定：同文本重复出现的最小单元数（按不同单元计） */
    private int runningTextMinPages;

    /** 页码判定：跨单元同址判定的横向中心容差（pt） */
    private double pageNumberXTolerance;

    /** 页码判定：跨单元同址成组所需的最少单元数 */
    private int pageNumberMinPages;

    /** 页码判定：内容空洞度——页码行与其正上方最近正文行之间的空白下限（行高倍数） */
    private double pageNumberIsolationRatio;

    /** 页眉候选区域：单元顶部高度占比 */
    private double headerAreaRatio;

    /** 页脚候选区域：单元底部高度占比 */
    private double footerAreaRatio;

    /**
     * 页眉页脚带判定阈值。
     *
     * @return 判定阈值
     */
    public HeaderFooterDetector.BandRules headerFooterRules() {
        return new HeaderFooterDetector.BandRules(headerAreaRatio, footerAreaRatio, runningTextMinPages,
                pageNumberMinPages, pageNumberXTolerance, pageNumberIsolationRatio);
    }

    /**
     * 启动期校验页眉页脚阈值（配置面是唯一基线，越界必须在启动时报错而不是运行期静默）。
     */
    @PostConstruct
    void validate() {
        requireAtLeast("running-text-min-pages", runningTextMinPages, 2);
        requireNonNegative("page-number-xtolerance", pageNumberXTolerance);
        requireAtLeast("page-number-min-pages", pageNumberMinPages, 2);
        requirePositive("page-number-isolation-ratio", pageNumberIsolationRatio);
        requireRatio("header-area-ratio", headerAreaRatio);
        requireRatio("footer-area-ratio", footerAreaRatio);
        if (headerAreaRatio + footerAreaRatio >= 1) {
            fail("header-area-ratio/footer-area-ratio", "两者之和必须小于 1",
                    headerAreaRatio + footerAreaRatio);
        }
    }

    /** 整数下限校验 */
    private void requireAtLeast(String key, int value, int min) {
        if (value < min) {
            fail(key, "不得小于 " + min, value);
        }
    }

    /** 非负校验 */
    private void requireNonNegative(String key, double value) {
        if (value < 0) {
            fail(key, "不得为负", value);
        }
    }

    /** 正数校验 */
    private void requirePositive(String key, double value) {
        if (value <= 0) {
            fail(key, "必须大于 0", value);
        }
    }

    /** 比例校验（0, 1) 区间） */
    private void requireRatio(String key, double value) {
        if (value <= 0 || value >= 1) {
            fail(key, "必须在 (0, 1) 区间", value);
        }
    }

    /** 抛出带配置键的启动失败 */
    private void fail(String key, String rule, Object value) {
        throw new IllegalStateException("knowledge.structure." + key + " " + rule + "，当前值=" + value);
    }
}
