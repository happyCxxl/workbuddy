package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 按栏沟切行的换栏判定：同一行被拆成多个文本对象续排时不切，新栏起点回跳到行首左侧才切。
 */
class PdfLineSupportGutterSplitTest {

    private final ParseProperties properties = PropertiesFixtures.parse();

    /**
     * 同一行第二个文本对象从行内继续（起点仍在行首右侧）：不切，仍是一行。
     *
     * <p>守护 {@code continuationRunShouldNotSplitLine}。
     */
    @Test
    void continuationRunShouldNotSplitLine() {
        List<CharInfo> band = new ArrayList<>(run(10, 'A'));
        band.addAll(run(70.2, 'A'));

        List<PageLine> lines = PdfLineSupport.buildLines(1, band, List.of(50.0),
                LineTexts.Options.of(properties), properties);

        assertEquals(1, lines.size());
        assertEquals("AAAAAAAAAAAA", lines.getFirst().text());
    }

    /**
     * 新栏起点回跳到行首左侧：按栏沟切开，成为两行。
     *
     * <p>守护 {@code restartLeftOfLineStartShouldSplit}。
     */
    @Test
    void restartLeftOfLineStartShouldSplit() {
        List<CharInfo> band = new ArrayList<>(run(70, 'B'));
        band.addAll(run(10, 'A'));

        List<PageLine> lines = PdfLineSupport.buildLines(1, band, List.of(50.0),
                LineTexts.Options.of(properties), properties);

        assertEquals(2, lines.size());
        assertEquals("BBBBBB", lines.get(0).text());
        assertEquals("AAAAAA", lines.get(1).text());
    }

    /** 连续排列的 6 个字符（字宽 10、字号 6、同一 y） */
    private static List<CharInfo> run(double startX, char codePoint) {
        List<CharInfo> chars = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            chars.add(new CharInfo(startX + i * 10, 0, 10, 6, 6, "Helvetica", false, codePoint));
        }
        return chars;
    }
}
