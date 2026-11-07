package com.workbuddy.worker.structure.impl.craft;

import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.domain.parse.UnitLine;
import com.workbuddy.common.enums.parse.UnitType;
import com.workbuddy.worker.structure.craft.HeaderFooterDetector;
import com.workbuddy.worker.structure.craft.HeaderFooterDetector.BandRules;
import com.workbuddy.worker.structure.craft.HeaderFooterDetector.UnitBands;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组装侧页眉页脚带判定：跨单元重复 + 页带几何 + 页码同址/内容空洞。
 *
 * <p>守护 {@code detect}：只认跨单元重复文本与孤立页码，单页出现的页带文本不算页眉页脚；
 * 判错会把正文首行当下一条页眉删掉，或把紧贴正文的页底数字当页码。
 */
class HeaderFooterDetectorTest {

    private static final double PAGE_HEIGHT = 800;
    private static final BandRules RULES = new BandRules(0.1, 0.1, 2, 2, 20, 1.5);

    /** 页顶/页底重复文本达阈值：各单元同行判为页眉页脚。 */
    @Test
    void detectShouldMarkRepeatedHeaderAndFooter() {
        List<UnitBands> bands = HeaderFooterDetector.detect(List.of(
                unit(1, line("招标文件", 60, 40), line("某某公司", 60, 750)),
                unit(2, line("招标文件", 60, 40), line("某某公司", 60, 750)),
                unit(3, line("招标文件", 60, 40), line("某某公司", 60, 750))), RULES);

        assertEquals(3, bands.size());
        for (UnitBands band : bands) {
            assertEquals(List.of("招标文件"), texts(band.headers()));
            assertEquals(List.of("某某公司"), texts(band.footers()));
        }
    }

    /** 只在单页出现的页带文本不判页眉（正文首行常落在页顶带）。 */
    @Test
    void detectShouldIgnoreOneOffBandText() {
        List<UnitBands> bands = HeaderFooterDetector.detect(List.of(
                unit(1, line("第一页附注", 60, 40)),
                unit(2, line("第二页附注", 60, 40))), RULES);

        assertTrue(bands.get(0).headers().isEmpty(), () -> "headers=" + texts(bands.get(0).headers()));
        assertTrue(bands.get(1).headers().isEmpty(), () -> "headers=" + texts(bands.get(1).headers()));
    }

    /** 页脚带内重复文本剥离行尾页码后仍能聚合（「项目名称 1」与「项目名称 2」同键）。 */
    @Test
    void detectShouldAggregateFooterByBaseText() {
        List<UnitBands> bands = HeaderFooterDetector.detect(List.of(
                unit(1, line("项目名称 1", 60, 750)),
                unit(2, line("项目名称 2", 60, 750))), RULES);

        assertEquals(List.of("项目名称 1"), texts(bands.get(0).footers()));
        assertEquals(List.of("项目名称 2"), texts(bands.get(1).footers()));
    }

    /** 孤立页码：同地址跨单元出现且与正文留白，判页脚。 */
    @Test
    void detectShouldMarkIsolatedPageNumber() {
        List<UnitBands> bands = HeaderFooterDetector.detect(List.of(
                unit(1, line("正文", 100, 100), line("1", 290, 760)),
                unit(2, line("正文", 100, 100), line("2", 290, 760)),
                unit(3, line("正文", 100, 100), line("3", 290, 760))), RULES);

        for (UnitBands band : bands) {
            assertEquals(1, band.footers().size(), () -> "footers=" + texts(band.footers()));
        }
    }

    /** 紧贴正文的页底数字行不判页码（内容不空洞）。 */
    @Test
    void detectShouldIgnorePageNumberTouchingBody() {
        List<UnitBands> bands = HeaderFooterDetector.detect(List.of(
                unit(1, line("正文末行", 100, 705), line("1", 290, 725)),
                unit(2, line("正文末行", 100, 705), line("2", 290, 725))), RULES);

        for (UnitBands band : bands) {
            assertTrue(band.footers().isEmpty(), () -> "footers=" + texts(band.footers()));
        }
    }

    /** 无版面高度的单元（Office 无物理页）不做带判定。 */
    @Test
    void detectShouldSkipUnitWithoutGeometry() {
        ParseUnitResult unit = new ParseUnitResult();
        unit.setUnitIndex(1);
        unit.setType(UnitType.VIRTUAL_PAGE);
        unit.setLines(List.of(line("招标文件", 60, 40), line("某某公司", 60, 750)));
        ParseUnitResult same = new ParseUnitResult();
        same.setUnitIndex(2);
        same.setType(UnitType.VIRTUAL_PAGE);
        same.setLines(List.of(line("招标文件", 60, 40), line("某某公司", 60, 750)));

        List<UnitBands> bands = HeaderFooterDetector.detect(List.of(unit, same), RULES);

        assertTrue(bands.get(0).headers().isEmpty());
        assertTrue(bands.get(0).footers().isEmpty());
    }

    /** 单元夹具：版面高度固定 800pt。 */
    private ParseUnitResult unit(int index, UnitLine... lines) {
        ParseUnitResult unit = new ParseUnitResult();
        unit.setUnitIndex(index);
        unit.setType(UnitType.PAGE);
        unit.setLayoutHeight(PAGE_HEIGHT);
        unit.setLines(List.of(lines));
        return unit;
    }

    /** 行夹具：宽 200pt、高 10pt。 */
    private UnitLine line(String text, double x, double y) {
        return new UnitLine(text, x, y, 200, 10);
    }

    private List<String> texts(List<UnitLine> lines) {
        return lines.stream().map(UnitLine::getText).toList();
    }
}
