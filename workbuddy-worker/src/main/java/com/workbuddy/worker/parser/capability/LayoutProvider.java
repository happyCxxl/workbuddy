package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;

/**
 * 版面分析能力接口：复杂页的阅读顺序交本接口补强。
 * 值对象见 common.domain.parse（LayoutResult/PageImage/LayoutRegion）。
 *
 * @author cxxl
 */
public interface LayoutProvider {

    /**
     * 分析页面版面。
     *
     * @param page 页面图像
     * @param ctx  调用上下文
     * @return 版面结果（区域分类 + 阅读顺序）
     */
    LayoutResult analyze(PageImage page, ProviderContext ctx);
}
