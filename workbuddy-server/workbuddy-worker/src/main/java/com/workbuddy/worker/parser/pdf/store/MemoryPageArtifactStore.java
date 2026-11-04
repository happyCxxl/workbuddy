package com.workbuddy.worker.parser.pdf.store;

import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.model.PageContent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存页产物存储：仅用于单窗口文档，所有页产物都必须保留到装配完成，不做淘汰。
 */
final class MemoryPageArtifactStore implements PageArtifactStore {

    private final Map<Integer, List<PageContent>> windows = new LinkedHashMap<>();

    /**
     * 写入单窗口页产物（内存直接持有）。
     *
     * @param fromPage 窗口起始页
     * @param pages    页产物
     */
    @Override
    public void writeWindow(int fromPage, List<PageContent> pages) {
        validateWindow(fromPage, pages);
        windows.put(fromPage, List.copyOf(pages));
    }

    /**
     * 读取单窗口页产物；缺失即失败。
     *
     * @param fromPage 窗口起始页
     * @return 页产物
     */
    @Override
    public List<PageContent> readWindow(int fromPage) {
        List<PageContent> pages = windows.get(fromPage);
        if (NullUtil.isNull(pages)) {
            throw new PageArtifactStoreException("页产物窗口不存在: " + fromPage);
        }
        return pages;
    }

    /** 清空内存中的页产物。 */
    @Override
    public void close() {
        windows.clear();
    }

    /** 校验窗口页码连续，避免第一遍写入错位后第二遍静默装配错页 */
    private static void validateWindow(int fromPage, List<PageContent> pages) {
        ThrowUtil.throwIf(fromPage <= 0 || NullUtil.isNull(pages) || pages.isEmpty(),
                () -> new PageArtifactStoreException("页产物窗口为空或起始页非法: " + fromPage));
        for (int i = 0; i < pages.size(); i++) {
            PageContent page = pages.get(i);
            int index = i;
            int expected = fromPage + index;
            ThrowUtil.throwIf(NullUtil.isNull(page) || page.pageNo() != expected,
                    () -> new PageArtifactStoreException(
                            "页产物页码不连续: fromPage=" + fromPage + ", index=" + index));
        }
    }
}
