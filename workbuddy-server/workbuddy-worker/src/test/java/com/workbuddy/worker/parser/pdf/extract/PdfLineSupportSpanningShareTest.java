package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 栏沟复核口径：整行横越栏沟的正文行占比用于识别「假栏沟」（表格内部列间隙一类的局部几何空隙）。
 */
class PdfLineSupportSpanningShareTest {

    /** 栏沟中心 x */
    private static final double GUTTER = 120;

    /**
     * 守护 {@code fullWidthLinesShouldVetoGutter}：整页宽度的正文行成片越沟 → 占比高，
     * 该「栏沟」不成立（真实样本第 3 页的表格列间隙即此形态）。
     */
    @Test
    void fullWidthLinesShouldVetoGutter() {
        PageLine wide = line("ABCDEFGH", 100);
        PageLine wideSecond = line("IJKLMNOP", 100);
        PageLine columnLine = line("XYZ", 130);

        double share = PdfLineSupport.spanningShare(List.of(wide, wideSecond, columnLine), List.of(GUTTER));

        assertEquals(2.0 / 3, share, 1e-9);
        assertTrue(share > 0.3, () -> "share=" + share);
    }

    /** 守护 {@code columnLinesShouldKeepGutter}：栏内行不越沟 → 占比为 0，真栏沟保留。 */
    @Test
    void columnLinesShouldKeepGutter() {
        PageLine left = line("ABC", 100);
        PageLine right = line("XYZ", 130);

        assertEquals(0, PdfLineSupport.spanningShare(List.of(left, right), List.of(GUTTER)));
    }

    /** 无栏沟（单栏页）时占比为 0，不做复核判定（{@code withoutGutterShareShouldBeZero}）。 */
    @Test
    void withoutGutterShareShouldBeZero() {
        assertEquals(0, PdfLineSupport.spanningShare(List.of(line("ABC", 100)), List.of()));
    }

    /** 造一行：字符按 5pt 字宽从左边界连续排布 */
    private static PageLine line(String text, double x) {
        List<CharInfo> chars = new ArrayList<>();
        double cursor = x;
        for (int i = 0; i < text.length(); i++) {
            chars.add(new CharInfo(cursor, 100, 5, 10, 10, "Helvetica", false, text.charAt(i)));
            cursor += 5;
        }
        return new PageLine(1, x, 100, cursor - x, 10, text, 10, "Helvetica", false,
                List.of(), chars, false);
    }
}
