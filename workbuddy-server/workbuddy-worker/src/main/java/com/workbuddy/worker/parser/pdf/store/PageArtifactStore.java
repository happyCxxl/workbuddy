package com.workbuddy.worker.parser.pdf.store;

import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.worker.parser.pdf.model.PageContent;

import java.util.List;

/**
 * PDF 页产物存储：第一遍逐页提取后写入，装配阶段按窗口读取。
 * 生命周期由一次 PDF 解析持有；第二遍读取不得回落重新解析。
 */
public interface PageArtifactStore extends AutoCloseable {

    /**
     * 写入一个窗口的页产物。
     *
     * @param fromPage 窗口起始页（从 1 开始）
     * @param pages    窗口内页产物（按页码顺序）
     */
    void writeWindow(int fromPage, List<PageContent> pages);

    /**
     * 读取一个窗口的页产物。
     *
     * @param fromPage 窗口起始页（从 1 开始）
     * @return 窗口内页产物
     */
    List<PageContent> readWindow(int fromPage);

    /**
     * 释放存储资源；磁盘实现删除临时目录。
     */
    @Override
    void close();

    /**
     * 按文档页数选择存储：单窗口走内存，多窗口走临时磁盘。
     *
     * @param pageCount  文档页数
     * @param windowSize 装配窗口大小（页）
     * @return 页产物存储
     */
    static PageArtifactStore open(int pageCount, int windowSize) {
        ThrowUtil.throwIf(windowSize <= 0, () -> new IllegalArgumentException("windowSize 必须大于 0"));
        if (pageCount <= windowSize) {
            return new MemoryPageArtifactStore();
        }
        return new DiskPageArtifactStore();
    }
}
