package com.workbuddy.worker.testkit;

import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.domain.parse.UnitLine;
import com.workbuddy.common.enums.parse.UnitType;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 单元夹具：把页提取内容装配成单元结果（行摘要 + 指标 + 页尺寸），供页面步骤单测构造工作区。
 */
public final class PageUnitFixtures {

    private PageUnitFixtures() {
    }

    /**
     * 页提取内容 → 单元结果。
     *
     * @param page 页提取内容
     * @return 单元结果（元素为空）
     */
    public static ParseUnitResult of(PageContent page) {
        ParseUnitResult unit = new ParseUnitResult();
        unit.setUnitIndex(page.pageNo());
        unit.setType(UnitType.PAGE);
        unit.setMetric(page.metric());
        unit.setLayoutHeight(page.pageHeight());
        List<UnitLine> lines = new ArrayList<>(page.lines().size());
        for (PageLine line : page.lines()) {
            lines.add(new UnitLine(line.text(), line.x(), line.y(), line.width(), line.height()));
        }
        unit.setLines(lines);
        return unit;
    }
}
