package com.workbuddy.worker.parser.office.impl;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOCX 页眉页脚与目录特征单测：header/footer 部件产元素、目录段候选标记。
 */
class DocxHeaderFooterTest {

    private final DocxTestFixture fixture = new DocxTestFixture();

    /**
     * 守护 {@code docxHeaderFooterPartsShouldEmitElements}：该场景行为改动即失败。
     */
    @Test
    void docxHeaderFooterPartsShouldEmitElements() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph body = doc.createParagraph();
            body.createRun().setText("正文内容");
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            header.createParagraph().createRun().setText("XX项目招标文件");
            XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
            footer.createParagraph().createRun().setText("第 1 页 共 2 页");

            ParseSource source = fixture.parse(DocxTestFixture.save(doc));

            ParseElement headerElement = source.getElements().stream()
                    .filter(e -> ElementType.HEADER.name().equals(e.getType()))
                    .findFirst().orElse(null);
            assertNotNull(headerElement);
            assertEquals("XX项目招标文件", headerElement.getText());
            assertTrue(headerElement.getProvenance().getPath().startsWith("docx#header"));

            ParseElement footerElement = source.getElements().stream()
                    .filter(e -> ElementType.FOOTER.name().equals(e.getType()))
                    .findFirst().orElse(null);
            assertNotNull(footerElement);
            assertTrue(footerElement.getText().contains("第 1 页"));
            assertTrue(footerElement.getProvenance().getPath().startsWith("docx#footer"));
        }
    }

    /**
     * 守护 {@code docxTocParagraphShouldMarkTocCandidate}：该场景行为改动即失败。
     */
    @Test
    void docxTocParagraphShouldMarkTocCandidate() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph toc = doc.createParagraph();
            toc.createRun().setText("第一章 总则 ...... 1");
            XWPFParagraph body = doc.createParagraph();
            body.createRun().setText("正文段落内容");

            ParseSource source = fixture.parse(DocxTestFixture.save(doc));

            ParseElement tocElement = source.getElements().stream()
                    .filter(e -> "第一章 总则 ...... 1".equals(e.getText()))
                    .findFirst().orElse(null);
            assertNotNull(tocElement);
            assertTrue(tocElement.getTocCandidate());
            ParseElement bodyElement = source.getElements().stream()
                    .filter(e -> "正文段落内容".equals(e.getText()))
                    .findFirst().orElse(null);
            assertNotNull(bodyElement);
            assertNotEquals(Boolean.TRUE, bodyElement.getTocCandidate());
        }
    }
}
