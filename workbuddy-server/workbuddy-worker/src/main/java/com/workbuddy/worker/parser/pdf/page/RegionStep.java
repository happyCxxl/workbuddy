package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.detect.RuleRegions;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 线框区域：按线段识别区域，并按行中心把正文行归入区域；区域内按 y 排序。
 */
public final class RegionStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "region";
    }

    /**
     * 依赖正文行。
     *
     * @return 依赖步骤名
     */
    @Override
    public Set<String> requires() {
        return Set.of();
    }

    /**
     * 计算区域与区域行。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        List<Region> regions = RuleRegions.regions(context.getPage().segments(),
                context.getProperties().getTableLineCoverRatio(),
                context.getPage().pageWidth(), context.getPage().pageHeight());
        Map<Region, List<PageLine>> regionLines = new LinkedHashMap<>();
        for (PageLine line : context.getBodyLines()) {
            Region region = PageRegions.regionOf(regions, line);
            if (NullUtil.isNotNull(region)) {
                regionLines.computeIfAbsent(region, key -> new ArrayList<>()).add(line);
            }
        }
        regionLines.values().forEach(lines -> lines.sort(Comparator.comparingDouble(PageLine::y)));
        context.setRegions(regions);
        context.setRegionLines(regionLines);
    }
}
