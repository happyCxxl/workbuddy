package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.pdf.asset.PdfPageImageRenderer;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterKeys;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单页工作区：一页的输入、外设口与页面局部产物；不持有全档聚合列表。
 */
public final class PageContext {

    private final PageContent page;
    private final HeaderFooterKeys keys;
    private final ParseProperties properties;
    private final CapabilityRegistry capabilityRegistry;
    private final ParseContext parseContext;
    private final PdfPageImageRenderer renderer;
    private final String fileId;

    private final ParseSource source = new ParseSource();
    private final List<ParseElement> elements = new ArrayList<>();
    private final List<ParseElement> footerElements = new ArrayList<>();

    private List<PageLine> bodyLines = List.of();
    private List<Region> regions = List.of();
    private Map<Region, List<PageLine>> regionLines = new LinkedHashMap<>();
    private List<PageLine> orderedLines = List.of();
    private List<PageBlock> blocks = List.of();

    /**
     * 构造单页工作区。
     *
     * @param page 页内容
     * @param keys 页眉/页脚键
     * @param properties 解析配置
     * @param capabilityRegistry 能力注册表
     * @param parseContext 解析上下文
     * @param renderer 页图渲染器；资产关闭时为 null
     * @param fileId 文件 ID
     */
    public PageContext(PageContent page, HeaderFooterKeys keys, ParseProperties properties,
                       CapabilityRegistry capabilityRegistry, ParseContext parseContext,
                       PdfPageImageRenderer renderer, String fileId) {
        this.page = page;
        this.keys = keys;
        this.properties = properties;
        this.capabilityRegistry = capabilityRegistry;
        this.parseContext = parseContext;
        this.renderer = renderer;
        this.fileId = fileId;
    }

    /**
     * 返回页内容。
     *
     * @return 页内容
     */
    public PageContent getPage() {
        return page;
    }

    /**
     * 返回页眉页脚识别键。
     *
     * @return 页眉页脚识别键
     */
    public HeaderFooterKeys getKeys() {
        return keys;
    }

    /**
     * 返回解析阈值配置。
     *
     * @return 解析阈值配置
     */
    public ParseProperties getProperties() {
        return properties;
    }

    /**
     * 返回能力注册表。
     *
     * @return 能力注册表
     */
    public CapabilityRegistry getCapabilityRegistry() {
        return capabilityRegistry;
    }

    /**
     * 返回解析环节上下文。
     *
     * @return 解析上下文
     */
    public ParseContext getParseContext() {
        return parseContext;
    }

    /**
     * 返回页图渲染器。
     *
     * @return 页图渲染器
     */
    public PdfPageImageRenderer getRenderer() {
        return renderer;
    }

    /**
     * 返回文件标识。
     *
     * @return 文件标识
     */
    public String getFileId() {
        return fileId;
    }

    /**
     * 返回页级源数据。
     *
     * @return 页级源数据
     */
    public ParseSource getSource() {
        return source;
    }

    /**
     * 返回页内元素。
     *
     * @return 页内元素
     */
    public List<ParseElement> getElements() {
        return elements;
    }

    /**
     * 返回暂存的页脚元素。
     *
     * @return 页脚元素
     */
    public List<ParseElement> getFooterElements() {
        return footerElements;
    }

    /**
     * 返回正文行。
     *
     * @return 正文行
     */
    public List<PageLine> getBodyLines() {
        return bodyLines;
    }

    /**
     * 设置正文行。
     *
     * @param bodyLines 正文行
     */
    public void setBodyLines(List<PageLine> bodyLines) {
        this.bodyLines = bodyLines;
    }

    /**
     * 返回线框区域。
     *
     * @return 线框区域
     */
    public List<Region> getRegions() {
        return regions;
    }

    /**
     * 设置线框区域。
     *
     * @param regions 线框区域
     */
    public void setRegions(List<Region> regions) {
        this.regions = regions;
    }

    /**
     * 返回按区域归组的行。
     *
     * @return 区域行
     */
    public Map<Region, List<PageLine>> getRegionLines() {
        return regionLines;
    }

    /**
     * 设置按区域归组的行。
     *
     * @param regionLines 区域行
     */
    public void setRegionLines(Map<Region, List<PageLine>> regionLines) {
        this.regionLines = regionLines;
    }

    /**
     * 返回阅读顺序行。
     *
     * @return 阅读顺序行
     */
    public List<PageLine> getOrderedLines() {
        return orderedLines;
    }

    /**
     * 设置阅读顺序行。
     *
     * @param orderedLines 阅读顺序行
     */
    public void setOrderedLines(List<PageLine> orderedLines) {
        this.orderedLines = orderedLines;
    }

    /**
     * 返回页内块。
     *
     * @return 页内块
     */
    public List<PageBlock> getBlocks() {
        return blocks;
    }

    /**
     * 设置页内块。
     *
     * @param blocks 页内块
     */
    public void setBlocks(List<PageBlock> blocks) {
        this.blocks = blocks;
    }
}
