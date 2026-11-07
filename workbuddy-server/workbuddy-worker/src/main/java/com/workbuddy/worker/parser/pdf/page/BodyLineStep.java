package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.List;
import java.util.Set;

/**
 * 正文行：排除页眉带命中行与页底页脚行，保持页内阅读顺序。
 */
public final class BodyLineStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "body-line";
    }

    /**
     * 不依赖其他页面步骤。
     *
     * @return 空集合
     */
    @Override
    public Set<String> requires() {
        return Set.of();
    }

    /**
     * 计算正文行。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        List<PageLine> bodyLines = context.getPage().lines().stream()
                .filter(line -> !PageBands.isHeaderLine(context, line))
                .filter(line -> !PageBands.isFooterLine(context, line))
                .toList();
        context.setBodyLines(bodyLines);
    }
}
