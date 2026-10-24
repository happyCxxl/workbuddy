package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
