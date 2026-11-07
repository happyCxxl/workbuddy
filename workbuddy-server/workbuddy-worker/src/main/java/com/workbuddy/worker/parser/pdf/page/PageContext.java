package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseFactSink;
import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.pdf.asset.PdfPageImageRenderer;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Region;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单页工作区：一页的输入、外设口与页面局部产物；不持有全档聚合列表。
 */
@Getter
public final class PageContext implements ParseFactSink {

    /**
     * -- GETTER --
     *  返回页内容。
     *
     */
    private final PageContent page;
    /**
     * -- GETTER --
     *  返回所属单元。
     *
     */
    private final ParseUnitResult unit;
    /**
     * -- GETTER --
     *  返回解析阈值配置。
     *
     */
    private final ParseProperties properties;
    /**
     * -- GETTER --
     *  返回能力注册表。
     *
     */
    private final CapabilityRegistry capabilityRegistry;
    /**
     * -- GETTER --
     *  返回解析环节上下文。
     *
     */
    private final ParseContext parseContext;
    /**
     * -- GETTER --
     *  返回页图渲染器。
     *
     */
    private final PdfPageImageRenderer renderer;
    /**
     * -- GETTER --
     *  返回文件标识。
     *
     */
    private final String fileId;

    /**
     * -- GETTER --
     *  返回页内元素。
     *
     */
    private final List<ParseElement> elements = new ArrayList<>();

    /** 页内事实信号（跨页合并由调用方按页序完成） */
    private final List<ParseFact> facts = new ArrayList<>();

    /** 页内能力使用记录（跨页合并由调用方按页序完成） */
    private final List<CapabilityUsage> capabilities = new ArrayList<>();

    /**
     * -- SETTER --
     *  设置正文行。
     * -- GETTER --
     *  返回正文行。
     *
     */
    @Setter
    private List<PageLine> bodyLines;
    /**
     * -- SETTER --
     *  设置线框区域。
     * -- GETTER --
     *  返回线框区域。
     *
     */
    @Setter
    private List<Region> regions = List.of();
    /**
     * -- SETTER --
     *  设置按区域归组的行。
     * -- GETTER --
     *  返回按区域归组的行。
     *
     */
    @Setter
    private Map<Region, List<PageLine>> regionLines = new LinkedHashMap<>();
    /**
     * -- SETTER --
     *  设置阅读顺序行。
     * -- GETTER --
     *  返回阅读顺序行。
     *
     */
    @Setter
    private List<PageLine> orderedLines = List.of();
    /**
     * -- SETTER --
     *  设置页内块。
     * -- GETTER --
     *  返回页内块。
     *
     */
    @Setter
    private List<PageBlock> blocks = List.of();

    /**
     * 构造单页工作区。
     *
     * @param page 页内容
     * @param unit 所属单元（行摘要与尺寸）
     * @param properties 解析配置
     * @param capabilityRegistry 能力注册表
     * @param parseContext 解析上下文
     * @param renderer 页图渲染器；资产关闭时为 null
     * @param fileId 文件 ID
     */
    public PageContext(PageContent page, ParseUnitResult unit,
                       ParseProperties properties,
                       CapabilityRegistry capabilityRegistry, ParseContext parseContext,
                       PdfPageImageRenderer renderer, String fileId) {
        this.page = page;
        this.unit = unit;
        this.properties = properties;
        this.capabilityRegistry = capabilityRegistry;
        this.parseContext = parseContext;
        this.renderer = renderer;
        this.fileId = fileId;
        this.bodyLines = page.lines();
    }

}
