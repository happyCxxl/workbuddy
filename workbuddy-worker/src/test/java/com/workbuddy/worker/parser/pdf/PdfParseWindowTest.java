package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大文件分窗：窗口大小不影响产物（全档行摘要用于跨页识别、窗口只定装配粒度），
 * 跨窗口仍能识别页眉页脚；大输入走临时文件按需读取。
 */
class PdfParseWindowTest {

    /** 内存路径阈值（取足够大的值，走整档留内存） */
    private static final int IN_MEMORY_BYTES = 1_000_000_000;

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    /** 窗口大小 1（每页一窗）与整档单窗的产物一致 */
    @Test
    void windowedParseShouldMatchSingleWindowParse() throws Exception {
        byte[] data = buildThreePageDocument();

        assertEquals(fingerprint(parse(data, 50, IN_MEMORY_BYTES)),
                fingerprint(parse(data, 1, IN_MEMORY_BYTES)));
    }

    /** 分窗后跨页识别仍覆盖全档：页脚文本与页码按页各产一条 */
    @Test
    void footerDetectionShouldCoverAllPagesWhenWindowed() throws Exception {
        ParseSource source = parse(buildThreePageDocument(), 1, IN_MEMORY_BYTES);

        List<String> footers = source.getElements().stream()
                .filter(element -> ElementType.FOOTER.name().equals(element.getType()))
                .map(ParseElement::getText).toList();
        assertEquals(6, footers.size(), () -> "footers=" + footers);
        assertTrue(footers.containsAll(List.of("1", "2", "3")), () -> "footers=" + footers);
        assertEquals(3, footers.stream().filter(text -> text.contains("Footer")).count(),
                () -> "footers=" + footers);
    }

    /** 大输入落临时文件按需读取：产物与内存路径一致 */
    @Test
    void largeInputShouldLoadFromTempFileWithSameResult() throws Exception {
        byte[] data = buildThreePageDocument();

        assertEquals(fingerprint(parse(data, 50, IN_MEMORY_BYTES)),
                fingerprint(parse(data, 50, 1)));
    }

    /** 临时文件不残留 */
    @Test
    void tempFileShouldBeRemovedAfterParse() throws Exception {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        List<String> before = tempFiles(tempDir);

        parse(buildThreePageDocument(), 50, 1);

        List<String> after = tempFiles(tempDir);
        after.removeAll(before);
        assertEquals(List.of(), after, "临时文件未清理");
    }

    /** 临时目录里本解析器前缀的文件名 */
    private List<String> tempFiles(Path tempDir) throws Exception {
        try (var files = Files.list(tempDir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("kb-parse-"))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
    }

    private ParseSource parse(byte[] data, int windowSize, int largeFileMinBytes) {
        ParseContext context = context(data);
        context.getProperties().setPageWindowSize(windowSize);
        context.getProperties().setLargeFileMinBytes(largeFileMinBytes);
        return parser.parse(context);
    }

    /** 产物指纹：元素与单元格的类型 + ID + 文本 + 页码 + 溯源路径 */
    private List<String> fingerprint(ParseSource source) {
        List<String> result = new ArrayList<>();
        for (ParseElement element : source.getElements()) {
            result.add(line(element));
            if (element.getCells() != null) {
                element.getCells().forEach(cell -> result.add("CELL|" + line(cell)));
            }
        }
        return result;
    }

    private String line(ParseElement element) {
        return element.getType() + "|" + element.getId() + "|" + element.getText() + "|" + element.getPage()
                + "|" + (element.getProvenance() == null ? "-" : element.getProvenance().getPath());
    }

    private ParseContext context(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("window.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return context;
    }

    /** 造三页：正文 + 每页同址页脚文本 + 每页不同的页码 */
    private byte[] buildThreePageDocument() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 3; page++) {
                PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.beginText();
                    cs.newLineAtOffset(72, 400);
                    cs.showText("Body text of page " + page);
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(250, 30);
                    cs.showText("XX Project Footer");
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(500, 18);
                    cs.showText(String.valueOf(page + 1));
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
