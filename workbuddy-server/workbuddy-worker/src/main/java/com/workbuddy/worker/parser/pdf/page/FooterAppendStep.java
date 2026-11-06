package com.workbuddy.worker.parser.pdf.page;

import java.util.Set;

/**
 * 页脚追加：正文元素之后追加暂存的 FOOTER 元素。
 */
public final class FooterAppendStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "footer-append";
    }

    /**
     * 依赖块渲染与页脚收集。
     *
     * @return 依赖步骤名
     */
    @Override
    public Set<String> requires() {
        return Set.of("block-render", "footer-collect");
    }

    /**
     * 追加页脚。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        context.getElements().addAll(context.getFooterElements());
    }
}
