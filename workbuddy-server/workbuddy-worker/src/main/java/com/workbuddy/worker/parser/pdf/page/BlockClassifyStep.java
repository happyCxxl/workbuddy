package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Region;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 页面块分类：线框区域首次出现时结算区域块，其余连续行按段落/表格候选块切分。
 */
public final class BlockClassifyStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "block-classify";
    }

    /**
     * 依赖区域与阅读顺序。
     *
     * @return 依赖步骤名
     */
    @Override
    public Set<String> requires() {
        return Set.of("region", "reading-order");
    }

    /**
     * 生成有序块。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        List<PageBlock> blocks = new ArrayList<>();
        List<PageLine> run = new ArrayList<>();
        Set<Region> settled = new HashSet<>();
        for (PageLine line : context.getOrderedLines()) {
            Region region = PageRegions.regionOf(context.getRegions(), line);
            if (NullUtil.isNull(region)) {
                run.add(line);
                continue;
            }
            if (!run.isEmpty()) {
                blocks.addAll(BlockClassifier.classify(run, context.getProperties()));
                run = new ArrayList<>();
            }
            if (settled.add(region)) {
                blocks.add(new RegionTableBlock(region, context.getRegionLines().get(region)));
            }
        }
        if (!run.isEmpty()) {
            blocks.addAll(BlockClassifier.classify(run, context.getProperties()));
        }
        context.setBlocks(blocks);
    }
}
