package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Token;

import java.util.List;

/**
 * 复杂页判定：数「行内跨栏间隙」——同一行里相邻 token 的水平间隙超过门槛，说明这行横跨两栏以上，
 * 即提取阶段没有把多栏拆开（几何口径最不可靠的形态）。出现该形态的行数达门槛即判复杂页，
 * 版面能力只对复杂页发起调用，几何口径仍是默认。
 *
 * @author cxxl
 */
public final class LayoutComplexity {

    private LayoutComplexity() {
    }

    /**
     * 跨栏行数：行内存在至少一处超过门槛的相邻 token 间隙即计一行。
     *
     * @param lines      页面行
     * @param properties 解析阈值
     * @return 跨栏行数
     */
    public static int spanningLineCount(List<PageLine> lines, ParseProperties properties) {
        double gapThreshold = properties.getLayoutColumnStartTolerance()
                * properties.getLayoutSpanningGapFactor();
        int spanning = 0;
        for (PageLine line : lines) {
            if (hasSpanningGap(line.tokens(), gapThreshold)) {
                spanning++;
            }
        }
        return spanning;
    }

    /**
     * 是否复杂页。
     *
     * @param lines      页面行
     * @param properties 解析阈值
     * @return 跨栏行数达到 `layoutModelMinSpanningLines` 即为复杂页
     */
    public static boolean isComplex(List<PageLine> lines, ParseProperties properties) {
        return spanningLineCount(lines, properties) >= properties.getLayoutModelMinSpanningLines();
    }

    /** 行内是否存在跨栏间隙 */
    private static boolean hasSpanningGap(List<Token> tokens, double gapThreshold) {
        for (int index = 1; index < tokens.size(); index++) {
            double gap = tokens.get(index).xStart() - tokens.get(index - 1).xEnd();
            if (gap >= gapThreshold) {
                return true;
            }
        }
        return false;
    }
}
