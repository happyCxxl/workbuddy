package com.workbuddy.worker.structure.impl.craft;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.structure.ElementExtensionKey;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.pdf.PdfBoxDocumentParser;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.StructureProperties;
import com.workbuddy.worker.structure.impl.StructureJudgeRegistry;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 跨页续表标记单测：真实 PDF 的两页同表，解析只产单页表格事实，续页重复表头由组装环节标记。
 */
class TableContinuationMarkingTest {

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    private StructureProperties properties;

    @BeforeEach
    void setUp() {
        properties = PropertiesFixtures.structure();
    }

    /** 下一页重复表头：解析不产续表标记，组装按表头重复标记续页表（{@code repeatedHeaderShouldBeMarkedByAssembly}）。 */
    @Test
    void repeatedHeaderShouldBeMarkedByAssembly() throws Exception {
        List<UnifiedElement> elements = new NormalizerImpl()
                .normalize(List.of(parse(twoPages())), context()).getElements();

        List<UnifiedElement> tables = tables(elements);
        assertEquals(2, tables.size());
        assertNull(tables.getFirst().getExtension().get(ElementExtensionKey.HEADER_REPEATED.key()));

        resolver().markRepeatedHeaders(elements, properties.getContinuationOverlapRatio());

        assertEquals(Boolean.TRUE, tables.getLast().getExtension().get(ElementExtensionKey.HEADER_REPEATED.key()));
        assertNull(tables.getFirst().getExtension().get(ElementExtensionKey.HEADER_REPEATED.key()));
    }

    private TableContinuationResolverImpl resolver() {
        return new TableContinuationResolverImpl(new StructureJudgeRegistry(new ArrayList<>(), properties));
    }

    private AssembleContext context() {
        AssembleContext context = new AssembleContext();
        context.setProperties(properties);
        return context;
    }

    private List<UnifiedElement> tables(List<UnifiedElement> elements) {
        return elements.stream()
                .filter(element -> UnifiedElementType.TABLE.name().equals(element.getType()))
                .toList();
    }

    private ParseSource parse(byte[] data) {
        ParseContext context = new ParseContext();
        context.setProperties(PropertiesFixtures.parse());
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-continuation");
        fileRef.setFileName("continuation.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        return parser.parse(context);
    }

    /** 两页：页 1 表头 + 三行，页 2 重复表头 + 两行（同列同宽） */
    private byte[] twoPages() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            page(doc, new String[][] {{"Item", "Amount"}, {"A-100", "12.50"}, {"B-200", "8.00"}, {"C-300", "24.75"}});
            page(doc, new String[][] {{"Item", "Amount"}, {"D-400", "3.20"}, {"E-500", "6.40"}});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void page(PDDocument doc, String[][] rows) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            for (int i = 0; i < rows.length; i++) {
                double y = 720 - i * 18;
                cell(cs, font, 60, y, rows[i][0]);
                cell(cs, font, 430, y, rows[i][1]);
            }
        }
    }

    private void cell(PDPageContentStream cs, PDType1Font font, double x, double y, String value) throws Exception {
        cs.beginText();
        cs.setFont(font, 10);
        cs.newLineAtOffset((float) x, (float) y);
        cs.showText(value);
        cs.endText();
    }
}
