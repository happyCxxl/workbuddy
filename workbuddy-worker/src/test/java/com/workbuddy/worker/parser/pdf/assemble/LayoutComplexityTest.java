package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复杂页判定单测：行内相邻 token 间隙超过门槛即判该行跨栏（提取阶段没把多栏拆开），
 * 跨栏行数达到门槛判复杂页；普通词距不触发。
 *
 * @author cxxl
 */
class LayoutComplexityTest {

    /** 两行各含一处跨栏间隙（门槛 48pt）→ 两行跨栏即复杂页 */
    @Test
    void linesWithSpanningGapShouldBeComplex() {
        List<PageLine> lines = List.of(spanningLine(100), spanningLine(80));

        assertEquals(2, LayoutComplexity.spanningLineCount(lines, new ParseProperties()));
        assertTrue(LayoutComplexity.isComplex(lines, new ParseProperties()));
    }

    /** 普通词距（间隙远小于门槛）→ 不跨栏、不判复杂页 */
    @Test
    void normalWordGapsShouldNotBeComplex() {
        List<PageLine> lines = List.of(
                line(List.of(new Token("alpha", 72, 100), new Token("one", 105, 130)), 100),
                line(List.of(new Token("alpha", 72, 100), new Token("two", 105, 130)), 80));

        assertEquals(0, LayoutComplexity.spanningLineCount(lines, new ParseProperties()));
        assertFalse(LayoutComplexity.isComplex(lines, new ParseProperties()));
    }

    /** 只有一行跨栏：未达门槛（默认 2 行） */
    @Test
    void singleSpanningLineShouldNotBeComplex() {
        List<PageLine> lines = List.of(spanningLine(100),
                line(List.of(new Token("alpha", 72, 100), new Token("two", 105, 130)), 80));

        assertEquals(1, LayoutComplexity.spanningLineCount(lines, new ParseProperties()));
        assertFalse(LayoutComplexity.isComplex(lines, new ParseProperties()));
    }

    /** 门槛与间隙系数都可配 */
    @Test
    void thresholdsShouldBeConfigurable() {
        List<PageLine> lines = List.of(spanningLine(100));
        ParseProperties properties = new ParseProperties();
        properties.setLayoutModelMinSpanningLines(1);
        properties.setLayoutSpanningGapFactor(2.0);

        assertTrue(LayoutComplexity.isComplex(lines, properties));
    }

    /** 一行两个 token，中间留出跨栏间隙 */
    private PageLine spanningLine(double y) {
        return line(List.of(new Token("alpha", 72, 100), new Token("beta", 300, 330)), y);
    }

    private PageLine line(List<Token> tokens, double y) {
        return new PageLine(1, tokens.getFirst().xStart(), y, 200, 12, "text", 10, "Helvetica", false,
                tokens, List.of(), false);
    }
}
