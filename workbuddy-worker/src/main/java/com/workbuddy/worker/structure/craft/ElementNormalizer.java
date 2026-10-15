package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.structure.AssembleContext;

import java.util.List;

/**
 * 元素标准化：各路元素 → 统一元素雏形 + 坐标统一（左上角原点 + pt）。
 *
 * @author cxxl
 */
public interface ElementNormalizer {

    /**
     * 标准化。
     *
     * @param sources 多路解析结果（当前 native 单路）
     * @param context 组装上下文
     * @return 统一元素雏形列表与未识别类型的跳过计数（来源标记保留在 extension）
     */
    NormalizeOutcome normalize(List<ParseSource> sources, AssembleContext context);
}
