package com.workbuddy.worker.parser.pdf.store;

import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.model.PageContent;

import java.nio.file.Path;
import java.util.List;

/**
 * 页产物窗口的公共命名与校验：内存/磁盘实现共用，避免两套窗口规则漂移。
 */
final class PageArtifactSupport {

    private PageArtifactSupport() {
    }

    /**
     * 窗口文件路径（按起始页命名）。
     *
     * @param directory 页产物目录
     * @param fromPage  窗口起始页
     * @return 窗口文件路径
     */
    static Path fileOf(Path directory, int fromPage) {
        return directory.resolve("window-" + fromPage + ".json.gz");
    }

    /**
     * 校验窗口页码连续，损坏文件在读取阶段立即失败。
     *
     * @param fromPage 窗口起始页
     * @param pages    页产物
     */
    static void validateWindow(int fromPage, List<PageContent> pages) {
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
