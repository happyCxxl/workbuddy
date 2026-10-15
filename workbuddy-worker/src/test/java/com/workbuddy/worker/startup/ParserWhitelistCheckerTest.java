package com.workbuddy.worker.startup;

import com.workbuddy.worker.input.FileValidationProperties;
import com.workbuddy.worker.parser.office.impl.DocDocumentParser;
import com.workbuddy.worker.parser.office.impl.DocxDocumentParser;
import com.workbuddy.worker.parser.office.impl.ExcelDocumentParser;
import com.workbuddy.worker.parser.pdf.PdfBoxDocumentParser;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import com.workbuddy.worker.parser.DocumentParserPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 白名单与解析器一致性校验单测：真实解析器集合配默认可通过；白名单里有格式没解析器时启动期抛错；
 * 未知格式名直接暴露为配置错误。
 *
 * @author cxxl
 */
class ParserWhitelistCheckerTest {

    private final ParserWhitelistChecker checker = new ParserWhitelistChecker(
            realParsers(), new FileValidationProperties());

    /** 真实解析器集合 + 默认白名单：应通过 */
    @Test
    void defaultWhitelistShouldBeCoveredByRealParsers() {
        assertDoesNotThrow(() -> checker.checkWhitelist(realParsers(), List.of("PDF", "DOC", "DOCX", "XLS", "XLSX")));
    }

    /** 白名单里的格式没有解析器：启动期抛错（PPT/PPTX 已登记格式但无解析器） */
    @Test
    void whitelistWithoutParserShouldFailFast() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> checker.checkWhitelist(realParsers(), List.of("PDF", "PPTX")));

        assertTrue(error.getMessage().contains("PPTX"), () -> "message=" + error.getMessage());
    }

    /** 白名单里的未知格式名：配置错误直接暴露 */
    @Test
    void unknownFormatNameShouldFailFast() {
        assertThrows(IllegalArgumentException.class, () -> checker.checkWhitelist(realParsers(), List.of("PDFX")));
    }

    private List<DocumentParserPort> realParsers() {
        return List.of(new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer()),
                new DocxDocumentParser(), new DocDocumentParser(), new ExcelDocumentParser());
    }
}
