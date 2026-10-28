package com.workbuddy.worker.preprocessing;

import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.utils.NullUtil;

/**
 * 派生视图元素公共操作（剔除/跳过处置共用口径）。
 */
public final class ViewElementHelper {

    private ViewElementHelper() {
    }

    /**
     * 清空元素全部单元格的检索文本（保留展示文本；剔除与跳过处置共用）。
     *
     * @param element 表格视图元素
     */
    public static void clearCellTexts(ViewElement element) {
        if (NullUtil.isNotNull(element.getCells())) {
            for (ViewCell cell : element.getCells()) {
                cell.setNormalizedText(null);
            }
        }
    }
}
