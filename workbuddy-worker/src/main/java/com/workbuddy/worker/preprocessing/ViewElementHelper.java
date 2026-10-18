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

    /** 清空元素全部单元格的 normalizedText（保留展示文本；EXCLUDE/BACKUP 跳过处置共用） */
    public static void clearCellTexts(ViewElement element) {
        if (NullUtil.isNotNull(element.getCells())) {
            for (ViewCell cell : element.getCells()) {
                cell.setNormalizedText(null);
            }
        }
    }
}
