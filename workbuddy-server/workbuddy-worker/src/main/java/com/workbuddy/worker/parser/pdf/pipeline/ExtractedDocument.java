package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.worker.parser.pdf.store.PageArtifactStore;

import java.util.List;

/**
 * 逐页提取结果：单元集合（每页一个单元，已带行摘要、指标与页尺寸）与页产物存储。
 *
 * <p>页产物按窗口存入 {@link PageArtifactStore}，装配阶段只读不重解析；
 * 单元内容在装配阶段回填元素，行摘要与指标在提取阶段即定型。
 */
public final class ExtractedDocument implements AutoCloseable {

    private final PageArtifactStore store;
    private final List<ParseUnitResult> units;

    /**
     * 构造提取结果。
     *
     * @param store 页产物存储
     * @param units 单元集合（按页序）
     */
    public ExtractedDocument(PageArtifactStore store, List<ParseUnitResult> units) {
        this.store = store;
        this.units = units;
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
     * 单元集合（按页序；元素在装配阶段回填）。
     *
     * @return 单元集合
     */
    public List<ParseUnitResult> units() {
        return units;
    }

    /**
     * 页数。
     *
     * @return 文档页数
     */
    public int pageCount() {
        return units.size();
    }

    /**
     * 释放页产物存储。
     */
    @Override
    public void close() {
        store.close();
    }
}
