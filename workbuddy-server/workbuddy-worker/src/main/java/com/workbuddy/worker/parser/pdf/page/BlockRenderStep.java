package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.assemble.TextRunRenderer;

import java.util.Set;

/**
 * 页面块渲染：按块顺序产出段落/表格元素，不决定块边界。
 */
public final class BlockRenderStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "block-render";
    }

    /**
     * 依赖块分类。
     *
     * @return 依赖步骤名
     */
    @Override
    public Set<String> requires() {
        return Set.of("block-classify");
    }

    /**
     * 渲染所有块。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        TextRunRenderer.render(context.getBlocks(), context.getPage(), context.getElements(),
                context.getSource(), context.getFileId(), context.getProperties(),
                context.getCapabilityRegistry(), context.getParseContext(), context.getRenderer());
    }
}
