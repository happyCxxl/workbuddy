package com.workbuddy.common.domain.parse;

import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.ParseSourceType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 按来源分组的一路解析结果。各路不预先合并——保真与证据保留，合并裁决归组装环节。
 * 当前只有 native 路；ocr/layout/table 路随能力接入新增。
 */
@Data
public class ParseSource {

    /** 路名（ParseSourceType 枚举值：native / ocr / layout / table；与事实类型 SignalType 同名的取值语义不同） */
    private String source;

    /** 具体解析器/提供方（如 pdfbox-3.0.4） */
    private String provider;

    /** 该路是否带候选顺序信息（预留未实现：组装环节阅读顺序当前不消费该字段） */
    private Boolean candidateOrder;

    /** 该路产出的元素列表 */
    private List<ParseElement> elements = new ArrayList<>();

    /** 单元级原始指标（PDF 与 Office 都回填；判定器按阈值转信号） */
    private List<PageMetric> pageMetrics = new ArrayList<>();

    /** 解析期发现的事实信号（结构缺口/表格规则失败等；判定器汇总） */
    private List<ParseFact> facts = new ArrayList<>();

    /** 判定单元总数（PDF 页数 / Excel sheet 数 / Word 按 officePageChars 折算的虚拟单元数；由解析器回填） */
    private Integer unitCount;

    /** 页面尺寸（PDF 解析器回填；组装环节构建 UnifiedDocument.pages 的输入） */
    private List<PageDimension> pageDimensions = new ArrayList<>();

    /** 路级说明（如 ocr 路预留 note） */
    private String note;

    /** 本路实际用到的模型能力（模型名 + 版本；能力调用处回填，管线汇总进产物能力快照） */
    private List<CapabilityUsage> capabilities = new ArrayList<>();

    /**
     * 原生解析路（不调用模型能力）。
     *
     * @param provider 解析提供方标识
     * @return 解析来源
     */
    public static ParseSource nativeSource(String provider) {
        ParseSource source = new ParseSource();
        source.source = ParseSourceType.NATIVE.value();
        source.provider = provider;
        source.candidateOrder = true;
        return source;
    }
}
