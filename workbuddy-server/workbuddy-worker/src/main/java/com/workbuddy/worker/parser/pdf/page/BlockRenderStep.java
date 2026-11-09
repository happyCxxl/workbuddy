package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.page.table.TableBuildChain;
import com.workbuddy.worker.parser.pdf.page.table.TableBuilder;

import java.util.Set;

/**
 * 页面块渲染：按块类型做块→策略选择——段落块走段落组装，表格块交表格回退链。
 */
public final class BlockRenderStep implements PageStep {

    /** 表格回退链：无状态，跨页并发复用 */
    private final TableBuildChain tableChain = new TableBuildChain();

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
        for (PageBlock block : context.getBlocks()) {
            switch (block) {
                case ParagraphBlock paragraph ->
                        ParagraphAssembler.addParagraph(paragraph.lines(), context.getElements(),
                                context.getFileId(), context.getPage());
                case TableBlock table ->
                        tableChain.buildText(new TableBuilder.Input(context, table.lines(), null));
                case RegionTableBlock region ->
                        tableChain.buildRuled(new TableBuilder.Input(context, region.lines(), region.region()));
            }
        }
    }
}
