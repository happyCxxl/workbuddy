package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.capability.TableCell;
import com.workbuddy.common.domain.parse.capability.TableResult;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表格能力接线的回归单测：注册表为 null（能力默认关）时规则路径照常出表，且不出现任何能力留痕事实 ——
 * 接线引入的入参不改变默认行为。
 *
 * @author cxxl
 */
class PdfTableCapabilityWiringTest {

    /** 两行两列宽间距文本：规则路径自己聚成 2×2 表；注册表为 null 时不接能力、不留痕 */
    @Test
    void withoutCapabilityShouldKeepRulePathUntouched() throws Exception {
        ParseSource source = parse(buildTwoColumnBlockPdf(), null);

        List<ParseElement> tables = source.getElements().stream()
                .filter(element -> ElementType.TABLE.name().equals(element.getType())).toList();
        assertEquals(1, tables.size(), () -> "elements=" + source.getElements());
        assertEquals(2, tables.getFirst().getRows());
        assertEquals(2, tables.getFirst().getCols());
        assertEquals(4, tables.getFirst().getCells().size());
        assertFalse(source.getFacts().stream().anyMatch(fact -> fact.getEvidence().contains("能力补强")),
                () -> "facts=" + source.getFacts());
    }

    /** 能力补强：token 逐行漂移使列支撑不足 → 规则失败，桩 provider 给出的结构成为产物表格 */
    @Test
    void failedTableShouldBeEnhancedByCapability() throws Exception {
        ParseSource source = parse(buildDriftingBlockPdf(), stubRegistry());

        List<ParseElement> tables = source.getElements().stream()
                .filter(element -> ElementType.TABLE.name().equals(element.getType())).toList();
        assertEquals(1, tables.size(), () -> "elements=" + source.getElements());
        assertEquals(3, tables.getFirst().getRows(), "桩结果的行列数（规则路径给不出这张表）");
        assertEquals(9, tables.getFirst().getCols());
        assertEquals(1, tables.getFirst().getCells().size());
        assertEquals(2, tables.getFirst().getHeaderRow());
        assertTrue(source.getFacts().stream().anyMatch(fact -> fact.getEvidence().contains("能力补强")),
                () -> "facts=" + source.getFacts());
        assertEquals(1, source.getCapabilities().size(), () -> "capabilities=" + source.getCapabilities());
        assertEquals("stub-table", source.getCapabilities().getFirst().model());
    }

    private ParseSource parse(byte[] data, CapabilityRegistry registry) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("table-capability.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer(), registry).parse(context);
    }

    /** 造一页两行两列宽间距 ASCII 文本（Helvetica 装不下中文，夹具只用 ASCII） */
    private byte[] buildTwoColumnBlockPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                writeText(cs, 700, 72, "Item name");
                writeText(cs, 700, 400, "1");
                writeText(cs, 680, 72, "Sub item");
                writeText(cs, 680, 400, "2");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void writeText(PDPageContentStream cs, float y, float x, String text) throws Exception {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    /** 只接表格能力的注册表：桩 provider 给出 3×9 一张格（行列数与规则路径风格完全不同，来源一眼可辨） */
    private CapabilityRegistry stubRegistry() {
        TableResult result = new TableResult();
        result.setRows(3);
        result.setCols(9);
        result.setModel("stub-table");
        result.setVersion("0.1");
        TableCell header = new TableCell();
        header.setRow(2);
        header.setCol(4);
        header.setText("stub");
        header.setIsHeader(true);
        result.setCells(List.of(header));
        return new CapabilityRegistry() {
            @Override
            public StructuredOcrProvider ocr() {
                return null;
            }

            @Override
            public LayoutProvider layout() {
                return null;
            }

            @Override
            public TableStructureProvider table() {
                return (region, ctx) -> result;
            }
        };
    }

    /** 造一页三行、每行两个 token 且 x 逐行漂移（每个位置只被一行支撑 → 列支撑不足，列聚类失败） */
    private byte[] buildDriftingBlockPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                writeText(cs, 700, 72, "Alpha");
                writeText(cs, 700, 400, "1");
                writeText(cs, 680, 131, "Beta");
                writeText(cs, 680, 467, "2");
                writeText(cs, 660, 193, "Gamma");
                writeText(cs, 660, 531, "3");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
