package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行片段合并的边界：大间隙的左右逻辑页眉块不能被合并。
 */
class LineFragmentMergerTest {

    /**
     * 守护 {@code largeGapHeaderBlocksShouldNotMerge}：左右两个逻辑页眉块间隙很大，合并即失败。
     */
    @Test
    void largeGapHeaderBlocksShouldNotMerge() {
        PageLine left = new PageLine(1, 72, 42, 60, 10, "HEADER LEFT", 9, "Helvetica",
                false, List.of(), List.of(), false);
        PageLine right = new PageLine(1, 420, 42, 66, 10, "HEADER RIGHT", 9, "Helvetica",
                false, List.of(), List.of(), false);

        List<PageLine> merged = LineFragmentMerger.merge(List.of(left, right), 595, 2,
                LineTexts.Options.of(PropertiesFixtures.parse()));

        assertEquals(2, merged.size(), () -> "merged=" + merged);
    }

    /**
     * 守护 {@code wideAdjacentFragmentsShouldMerge}：单栏整行接近页宽时，相邻小间隙片段仍要合并。
     */
    @Test
    void wideAdjacentFragmentsShouldMerge() {
        PageLine left = fragment(72, 50, "leftfragme");
        PageLine right = fragment(122, 30, "right");

        List<PageLine> merged = LineFragmentMerger.merge(List.of(left, right), 100, 2,
                LineTexts.Options.of(PropertiesFixtures.parse()));

        assertEquals(1, merged.size(), () -> "merged=" + merged);
        assertTrue(merged.getFirst().text().contains("left"), () -> "merged=" + merged);
        assertTrue(merged.getFirst().text().contains("right"), () -> "merged=" + merged);
    }

    /** 按给定 x/宽度造一行字符事实 */
    private static PageLine fragment(float x, float width, String text) {
        List<CharInfo> chars = new ArrayList<>();
        float charWidth = width / text.length();
        for (int i = 0; i < text.length(); i++) {
            chars.add(new CharInfo(x + i * charWidth, 42, charWidth, 10, 10,
                    "Helvetica", false, text.charAt(i)));
        }
        return PdfTokenSupport.buildLine(1, chars, LineTexts.Options.of(PropertiesFixtures.parse()));
    }
}
