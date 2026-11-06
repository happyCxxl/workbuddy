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

    public PageContent getPage() {
        return page;
    }

    public HeaderFooterKeys getKeys() {
        return keys;
    }

    public ParseProperties getProperties() {
        return properties;
    }

    public CapabilityRegistry getCapabilityRegistry() {
        return capabilityRegistry;
    }

    public ParseContext getParseContext() {
        return parseContext;
    }

    public PdfPageImageRenderer getRenderer() {
        return renderer;
    }

    public String getFileId() {
        return fileId;
    }

    public ParseSource getSource() {
        return source;
    }

    public List<ParseElement> getElements() {
        return elements;
    }

    public List<ParseElement> getFooterElements() {
        return footerElements;
    }

    public List<PageLine> getBodyLines() {
        return bodyLines;
    }

    public void setBodyLines(List<PageLine> bodyLines) {
        this.bodyLines = bodyLines;
    }

    public List<Region> getRegions() {
        return regions;
    }

    public void setRegions(List<Region> regions) {
        this.regions = regions;
    }

    public Map<Region, List<PageLine>> getRegionLines() {
        return regionLines;
    }

    public void setRegionLines(Map<Region, List<PageLine>> regionLines) {
        this.regionLines = regionLines;
    }

    public List<PageLine> getOrderedLines() {
        return orderedLines;
    }

    public void setOrderedLines(List<PageLine> orderedLines) {
        this.orderedLines = orderedLines;
    }

    public List<PageBlock> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<PageBlock> blocks) {
        this.blocks = blocks;
    }
}
