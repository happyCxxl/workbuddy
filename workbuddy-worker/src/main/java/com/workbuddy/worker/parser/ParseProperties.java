package com.workbuddy.worker.parser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 解析阈值配置（knowledge.parse 前缀；同名 kebab-case 键全量落在 knowledge-biz 的
 * config/knowledge-parse.yml，由主文件 application.yml 的 spring.config.import 清单载入）。
 * 阈值为初值，待真实样本标定。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.parse")
public class ParseProperties {

    /** 大文件分窗：单窗口装配的页数（全档只留行级轻量摘要，字符与 token 只在窗口内保留） */
    private int pageWindowSize = 50;

    /** 大文件阈值：输入大于该字节数时落临时文件后按需读取（小于则整档留内存） */
    private int largeFileMinBytes = 8388608;

    /** 扫描页判定：单页非空白字符数低于该值 */
    private int scanPageMinChars = 50;

    /** 扫描页判定：图片覆盖面积占比门槛（整页无文本时，达到该值判扫描页，低于该值判空白页） */
    private double scanPageImageRatio = 0.5;

    /** 矢量页判定：整页无文本无图片时，矢量图形覆盖率达到该值判纯矢量页（不计失败，与真空白区分） */
    private double vectorPageMinRatio = 0.05;

    /** 版面能力路由：行内相邻 token 间隙达到 `layoutColumnStartTolerance × layoutSpanningGapFactor` 即判该行跨栏 */
    private double layoutSpanningGapFactor = 4.0;

    /** 版面能力路由：跨栏行数达到该值才判复杂页（几何优先，复杂页才问模型阅读顺序） */
    private int layoutModelMinSpanningLines = 2;

    /** 版面能力路由：行起点聚类的横向容差（pt） */
    private double layoutColumnStartTolerance = 12.0;

    /** Office 单元折算：Word 每多少非空白字符 = 1 个判定单元（Azure 文档智能页单位口径：3000 字符/页） */
    private int officePageChars = 3000;

    /** PDF 图片元素门槛：图片占页面积达到该比例才产 IMAGE 元素与事实（Docling bitmap_area_threshold 口径：0.05） */
    private double imageMinAreaRatio = 0.05;

    /** 乱码页判定：非 CJK/ASCII/常用中文标点字符占比 */
    private double garbledRateThreshold = 0.2;

    /** 乱码页判定：可打印乱码带（拉丁补充区）字符占比门槛，达到即按该比例计乱码率 */
    private double printableGarbledRateThreshold = 0.3;

    /** 乱码页判定：来自「Type0 且缺 ToUnicode 映射」字体的字符占比门槛（达到即按 CID 映射失败单列处置） */
    private double cidMappingMinRatio = 0.3;

    /** 图片页判定：文本 bbox 面积 ÷ 页面面积 */
    private double textAreaRatioThreshold = 0.05;

    /** 成功单元占比门槛（≥90% PARTIAL_SUCCESS，<90% FAILED） */
    private double successUnitRatio = 0.9;

    /** 短文档门槛：内容单元不超过该值时改看坏页绝对数（小分母上比例门槛等价零容错） */
    private int shortDocumentMaxUnits = 3;

    /** 短文档门槛：坏页不超过该值且仍有可读单元时不判失败 */
    private int shortDocumentMaxFailedUnits = 1;

    /** 质量维度汇总：坏页比例（失败页 ÷ 内容单元）达到该值时追加一条汇总告警 */
    private double qualityBadPageRatioThreshold = 0.3;

    /** 行聚合 y 容差（pt；待样本标定） */
    private double lineYTolerance = 2.0;

    /** 段落聚合：行间距与行高的比值上限（超过则分段；待样本标定） */
    private double paragraphGapRatio = 1.5;

    /** 行文本重建：字符间隙达到「字号 × 该比例」即视为显著间隙（用于识别字距加宽排版） */
    private double characterSpacingFloorRatio = 0.12;

    /** 行文本重建：字符间隙达到「字号 × 该比例」即视为词间空格（不依赖 PDFBox 的词回调） */
    private double wordGapRatio = 0.15;

    /** 行文本重建：显著间隙占该行字符间隙的比例达到该值即判为字距加宽排版（词边界不再补空格） */
    private double characterSpacingGapShare = 0.75;

    /** 页眉/页脚判定：同文本重复出现的最小页数（按不同页计） */
    private int runningTextMinPages = 2;

    /** 页码判定：跨页同址判定的横向中心容差（pt） */
    private double pageNumberXTolerance = 5.0;

    /** 页码判定：跨页同址成组所需的最少页数 */
    private int pageNumberMinPages = 2;

    /** 页码判定：内容空洞度——页码行与其正上方最近正文行之间的空白下限（行高倍数） */
    private double pageNumberIsolationRatio = 1.5;

    /** 页眉候选区域：页面顶部高度占比 */
    private double headerAreaRatio = 0.08;

    /** 页脚候选区域：页面底部高度占比 */
    private double footerAreaRatio = 0.08;

    /** 线网格：线段需覆盖区域边长的比例（低于该比例的线不参与网格） */
    private double tableLineCoverRatio = 0.6;

    /** 文本列聚类：一列需被多少比例的行支持（低于该比例的列剔除） */
    private double tableColumnSupportRatio = 0.5;

    /** 文本列聚类：起点合并容差 = 块内最大字号 × 该比例（大字号行的起点抖动随字号放大） */
    private double tableColumnToleranceRatio = 0.25;

    /** 表格行带判定：全页「对齐位置」（token 左起点或右边界）至少被多少行带支持 */
    private int tableAlignmentMinRows = 2;

    /** 表格行带判定：行内落在对齐位置上的 token 数下限（2 列的"对齐"与双栏正文无法区分，故取 3） */
    private int tableRowMinColumns = 3;

    /** 表格行归并：续行行距不超过「块内行距中位数 × 该比例」即并进上一行（单元格内换行） */
    private double tableRowInnerGapShare = 0.6;

    /** 假表门限：列内文本跨度占列宽的中位数超过该值即判正文分栏（表格单元格相对列宽是稀疏的） */
    private double tableColumnMaxFillRatio = 0.7;

    /** 跨页续表：上一页表格贴页底时，两表列 x 区间重合比例下限 */
    private double tableContinuationOverlapRatio = 0.5;

    /** 假表门限：非空单元格占比低于该值即弃表（空白率门禁） */
    private double tableMinFilledRatio = 0.1;

    /** 表头判定：首行含数字的单元格占比上限（超过该值判为无表头） */
    private double tableHeaderMaxNumericRatio = 0.3;

    /** 分栏：栏沟最小宽度（pt；连续低覆盖横向带达到该宽度才算栏沟） */
    private double columnGutterMinWidth = 10.0;

    /** 分栏：栏沟最大竖直覆盖比例（横向带内被字符覆盖的纵向范围占比低于该值才算栏沟） */
    private double columnGutterMaxCoverage = 0.25;

    /** 分栏：每栏最小字符占比（低于该比例不认该栏） */
    private double columnMinShare = 0.1;

    /** 分栏：每栏最少字符数 */
    private int columnMinChars = 10;

    /** 分栏：单页参与判定所需的最少字符数（低于该值不分栏） */
    private int columnMinPageChars = 100;

    /** 分栏：最大栏数 */
    private int columnMaxCount = 4;
}
