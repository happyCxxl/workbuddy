package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.worker.parser.pdf.model.PageLines;
import com.workbuddy.worker.parser.pdf.store.PageArtifactStore;

import java.util.List;

/**
 * 逐页提取结果：页产物存储 + 全档行摘要 + 页指标 + 页面尺寸。
 *
 * <p>页产物按窗口存入 {@link PageArtifactStore}，装配阶段只读不重解析；
 * 行摘要与指标保持全档，供跨页识别和最终结果组装。
 */
public final class ExtractedDocument implements AutoCloseable {

    private final PageArtifactStore store;
    private final List<PageLines> pageLines;
    private final List<PageMetric> pageMetrics;
    private final List<PageDimension> pageDimensions;
    private final int pageCount;

    /**
     * 构造提取结果。
     *
     * @param store 页产物存储
     * @param pageLines 全档行摘要
     * @param pageMetrics 全档页指标
     * @param pageDimensions 全档页面尺寸
     * @param pageCount 页数
     */
    public ExtractedDocument(PageArtifactStore store, List<PageLines> pageLines,
                             List<PageMetric> pageMetrics, List<PageDimension> pageDimensions,
                             int pageCount) {
        this.store = store;
        this.pageLines = List.copyOf(pageLines);
        this.pageMetrics = List.copyOf(pageMetrics);
        this.pageDimensions = List.copyOf(pageDimensions);
        this.pageCount = pageCount;
    }

    /**
     * 页产物存储。
     *
     * @return 页产物存储
     */
    public PageArtifactStore store() {
        return store;
    }

    /**
     * 全档行摘要。
     *
     * @return 按页顺序的行摘要
     */
    public List<PageLines> pageLines() {
        return pageLines;
    }

    /**
     * 全档页指标。
     *
     * @return 按页顺序的指标
     */
    public List<PageMetric> pageMetrics() {
        return pageMetrics;
    }

    /**
     * 全档页面尺寸。
     *
     * @return 按页顺序的尺寸
     */
    public List<PageDimension> pageDimensions() {
        return pageDimensions;
    }

    /**
     * 页数。
     *
     * @return 文档页数
     */
    public int pageCount() {
        return pageCount;
    }

    /**
     * 释放页产物存储。
     */
    @Override
    public void close() {
        store.close();
    }
}
