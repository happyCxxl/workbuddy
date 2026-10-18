package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.structure.AssembleContext;

import java.util.List;

/**
 * 阅读顺序统一：XY-cut 递归切分（行带→块→递归，上→下、左→右）；
 * 无坐标元素（Office）保持解析环节文档顺序直通；版面模型顺序优先（预留）。
 */
public interface ReadingOrderResolver {

    /**
     * 排序。
     *
     * @param elements 去重后的元素
     * @param context  组装上下文
     * @return 阅读顺序的元素列表
     */
    List<UnifiedElement> resolve(List<UnifiedElement> elements, AssembleContext context);
}
