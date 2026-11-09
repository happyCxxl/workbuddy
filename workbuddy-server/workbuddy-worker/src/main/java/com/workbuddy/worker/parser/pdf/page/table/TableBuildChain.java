package com.workbuddy.worker.parser.pdf.page.table;

import com.workbuddy.worker.parser.pdf.page.BlockClassifier;
import com.workbuddy.worker.parser.pdf.page.PageBlock;
import com.workbuddy.worker.parser.pdf.page.ParagraphAssembler;
import com.workbuddy.worker.parser.pdf.page.ParagraphBlock;
import com.workbuddy.worker.parser.pdf.page.RegionTableBlock;
import com.workbuddy.worker.parser.pdf.page.TableBlock;

/**
 * 表格回退链：固定顺序 线框 → 文本 → 模型结构 → 降级。
 *
 * <p>线框策略网格未拟合时把行块重新分类、逐块走文本路径；文本策略结构失败时试模型；
 * 所有策略都不产出时降级为段落并留痕。各策略不再互相调用，回退走向只写在链上。
 * 链与三个策略都无状态，可跨页并发复用。
 */
public final class TableBuildChain {

    private final RuledTableBuilder ruled = new RuledTableBuilder();
    private final TextTableBuilder text = new TextTableBuilder();
    private final ModelTableBuilder model = new ModelTableBuilder();

    /**
     * 线框区域块入口。
     *
     * @param input 构建输入（region 为线框区域）
     */
    public void buildRuled(TableBuilder.Input input) {
        TableBuilder.Result result = ruled.build(input);
        if (result.table() != null) {
            input.context().getElements().add(result.table());
            return;
        }
        switch (result.failure()) {
            case GRID_UNFIT -> renderAsText(input);
            case STRUCTURE_FAILED -> tryModel(input, result.reason());
            case UNWORTHY -> degrade(input, result.reason());
            default -> throw new IllegalStateException("未预期的线框构建失败类别: " + result.failure());
        }
    }

    /**
     * 文本表格块入口；单行块按段落产出（旧文本路径口径，不判失败）。
     *
     * @param input 构建输入（region 为 null）
     */
    public void buildText(TableBuilder.Input input) {
        if (input.lines().size() < 2) {
            ParagraphAssembler.addParagraph(input.lines(), input.context().getElements(),
                    input.context().getFileId(), input.context().getPage());
            return;
        }
        TableBuilder.Result result = text.build(input);
        if (result.table() != null) {
            input.context().getElements().add(result.table());
            return;
        }
        switch (result.failure()) {
            case STRUCTURE_FAILED -> tryModel(input, result.reason());
            case UNWORTHY -> degrade(input, result.reason());
            case GRID_UNFIT -> throw new IllegalStateException("文本策略不返回线网格失败");
            default -> throw new IllegalStateException("未预期的文本构建失败类别: " + result.failure());
        }
    }

    /** 规则结构失败后的模型补强：拿不到结构就按原失败理由降级 */
    private void tryModel(TableBuilder.Input input, String ruleReason) {
        TableBuilder.Result result = model.build(input);
        if (result.table() != null) {
            input.context().getElements().add(result.table());
            return;
        }
        degrade(input, ruleReason);
    }

    /** 链终止：出 TABLE 事实并把区域降级为段落 */
    private void degrade(TableBuilder.Input input, String reason) {
        TableSupport.degrade(input.lines(), input.context().getPage(), input.context().getElements(),
                input.context(), input.context().getFileId(), reason);
    }

    /** 网格未拟合的文本回退：重新分类后逐块走段落/表格路径（与旧文本回退口径一致） */
    private void renderAsText(TableBuilder.Input input) {
        for (PageBlock block : BlockClassifier.classify(input.lines(), input.context().getProperties())) {
            switch (block) {
                case ParagraphBlock paragraph -> ParagraphAssembler.addParagraph(paragraph.lines(),
                        input.context().getElements(), input.context().getFileId(), input.context().getPage());
                case TableBlock table -> buildText(input.withLines(table.lines()));
                case RegionTableBlock region ->
                        buildRuled(input.withLines(region.lines()).withRegion(region.region()));
            }
        }
    }
}
