package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.worker.parser.pdf.model.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对齐图剪枝单测：与他人共享列的行构成表格主体，独来独往的行被剪掉；全部连通时不动。
 */
class TableAlignmentPrunerTest {

    /** 三行共享两列（表格主体）+ 两行各占一列（混入正文）→ 只保留前三行 */
    @Test
    void rowsSharingColumnsShouldBeKept() {
        List<List<Token>> rows = List.of(
                List.of(token(72), token(300)),
                List.of(token(72), token(300)),
                List.of(token(72), token(300)),
                List.of(token(20), token(24)),
                List.of(token(500), token(504)));
        List<TableCandidateDetector.Column> columns = List.of(column(72), column(300), column(20), column(24),
                column(500), column(504));

        assertEquals(List.of(0, 1, 2), TableAlignmentPruner.keptRows(rows, columns));
    }

    /** 所有行都共享列 → 全部保留（顺序与下标不变） */
    @Test
    void fullyConnectedRowsShouldAllBeKept() {
        List<List<Token>> rows = List.of(
                List.of(token(72), token(300)),
                List.of(token(72), token(300)),
                List.of(token(72), token(300)));

        assertEquals(List.of(0, 1, 2),
                TableAlignmentPruner.keptRows(rows, List.of(column(72), column(300))));
    }

    /** 两个连通块大小相同时取 token 更多的那个 */
    @Test
    void largerTokenGroupShouldWinOnTie() {
        List<List<Token>> rows = List.of(
                List.of(token(72)),
                List.of(token(300)),
                List.of(token(500), token(520)));
        List<TableCandidateDetector.Column> columns = List.of(column(72), column(300), column(500), column(520));

        assertEquals(List.of(2), TableAlignmentPruner.keptRows(rows, columns));
    }

    /** 没有列或没有行：不剪（返回全部下标） */
    @Test
    void emptyInputShouldKeepEverything() {
        assertEquals(List.of(), TableAlignmentPruner.keptRows(List.of(), List.of(column(72))));
        assertEquals(List.of(0, 1), TableAlignmentPruner.keptRows(List.of(List.of(token(72)), List.of(token(72))),
                List.of()));
    }

    private Token token(double x) {
        return new Token("t", x, x + 20);
    }

    private TableCandidateDetector.Column column(double x) {
        return new TableCandidateDetector.Column(x, x + 20);
    }
}
