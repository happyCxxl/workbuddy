package com.workbuddy.worker.structure.impl.craft;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.structure.AssembleContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 元素标准化单测：表格单元格 row/col/isHeader 透传（切片环节表头随片与行组依赖的回归）。
 */
class NormalizerImplTest {

    private final NormalizerImpl normalizer = new NormalizerImpl();

    @Test
    void tableCellsShouldCarryRowColAndHeaderThroughNormalization() {
        ParseElement table = ParseElement.of("t1", ElementType.TABLE);
        table.setRows(2);
        table.setCols(2);
        table.setHeaderRow(0);

        ParseElement header = ParseElement.of("h1", ElementType.TABLE_CELL);
        header.setRow(0);
        header.setCol(0);
        header.setIsHeader(true);
        header.setText("评分项");

        ParseElement first = ParseElement.of("c1", ElementType.TABLE_CELL);
        first.setRow(1);
        first.setCol(0);
        first.setText("A1 报价");

        ParseElement second = ParseElement.of("c2", ElementType.TABLE_CELL);
        second.setRow(1);
        second.setCol(1);
        second.setText("30");

        table.setCells(List.of(header, first, second));

        ParseSource source = ParseSource.nativeSource("poi-5.4.0");
        source.setElements(List.of(table));

        List<UnifiedElement> result = normalizer.normalize(List.of(source), new AssembleContext()).getElements();

        UnifiedElement unifiedTable = result.getFirst();
        assertEquals(2, unifiedTable.getRows());
        assertEquals(0, unifiedTable.getHeaderRow());
        assertEquals(3, unifiedTable.getCells().size());

        UnifiedElement unifiedHeader = unifiedTable.getCells().getFirst();
        assertEquals(0, unifiedHeader.getRow());
        assertEquals(0, unifiedHeader.getCol());
        assertTrue(unifiedHeader.getIsHeader());

        UnifiedElement unifiedFirst = unifiedTable.getCells().get(1);
        assertEquals(1, unifiedFirst.getRow());
        assertEquals(0, unifiedFirst.getCol());
        assertNotEquals(Boolean.TRUE, unifiedFirst.getIsHeader());

        UnifiedElement unifiedSecond = unifiedTable.getCells().get(2);
        assertEquals(1, unifiedSecond.getRow());
        assertEquals(1, unifiedSecond.getCol());
    }

    @Test
    void extensionShouldCarrySourceRouteAndProvider() {
        ParseElement paragraph = ParseElement.of("p1", ElementType.PARAGRAPH);
        paragraph.setText("正文内容");
        ParseSource source = ParseSource.nativeSource("pdfbox-3.0.4");
        source.setElements(List.of(paragraph));

        List<UnifiedElement> result = normalizer.normalize(List.of(source), new AssembleContext()).getElements();

        Map<String, Object> extension = result.getFirst().getExtension();
        assertEquals("native", extension.get("source"));
        assertEquals("pdfbox-3.0.4", extension.get("provider"));
    }

    @Test
    void extensionShouldSkipProviderWhenSourceHasNone() {
        ParseElement paragraph = ParseElement.of("p1", ElementType.PARAGRAPH);
        paragraph.setText("正文内容");
        ParseSource source = ParseSource.nativeSource(null);
        source.setElements(List.of(paragraph));

        List<UnifiedElement> result = normalizer.normalize(List.of(source), new AssembleContext()).getElements();

        Map<String, Object> extension = result.getFirst().getExtension();
        assertEquals("native", extension.get("source"));
        assertFalse(extension.containsKey("provider"));
    }
}
