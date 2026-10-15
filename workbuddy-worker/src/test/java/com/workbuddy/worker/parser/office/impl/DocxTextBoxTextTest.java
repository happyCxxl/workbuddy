package com.workbuddy.worker.parser.office.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DOCX 文本框补取单测：`w:txbxContent` 内的 `w:t` 按出现顺序取出（POI 的 getText 不含文本框）；
 * 多个文本框拼接、无文本框给空串。
 *
 * @author cxxl
 */
class DocxTextBoxTextTest {

    /** 合并口径：段落里已有文本框文字时不再追加（真实样例暴露的重复问题） */
    @Test
    void mergeShouldNotDuplicateAlreadyCapturedTextBoxText() {
        assertEquals("正文一段", DocxDocumentParser.mergeTextBoxText("正文一段", "正文一段"));
        assertEquals("正文一段\n文本框文字", DocxDocumentParser.mergeTextBoxText("正文一段", "文本框文字"));
        assertEquals("文本框文字", DocxDocumentParser.mergeTextBoxText("", "文本框文字"));
        assertEquals("正文一段", DocxDocumentParser.mergeTextBoxText("正文一段", ""));
        assertEquals("", DocxDocumentParser.mergeTextBoxText(null, null));
    }

    /** 单个文本框：取出其中的 w:t 文本 */
    @Test
    void singleTextBoxShouldBeExtracted() {
        String xml = "<w:p><w:r><w:t>正文</w:t></w:r><w:r><w:drawing><wps:txbx><w:txbxContent>"
                + "<w:p><w:r><w:t>文本框文字</w:t></w:r></w:p></w:txbxContent></wps:txbx></w:drawing></w:r></w:p>";

        assertEquals("文本框文字", DocxDocumentParser.docxTextBoxText(xml));
    }

    /** 多个文本框：按出现顺序拼接 */
    @Test
    void multipleTextBoxesShouldBeConcatenatedInOrder() {
        String xml = "<w:p><w:txbxContent><w:p><w:r><w:t>第一</w:t></w:r></w:p></w:txbxContent>"
                + "<w:txbxContent><w:p><w:r><w:t>第二</w:t></w:r></w:p></w:txbxContent></w:p>";

        assertEquals("第一第二", DocxDocumentParser.docxTextBoxText(xml));
    }

    /** 无文本框 / 空输入：给空串（不误取正文 run 的文本） */
    @Test
    void paragraphWithoutTextBoxShouldGiveEmpty() {
        assertEquals("", DocxDocumentParser.docxTextBoxText("<w:p><w:r><w:t>正文</w:t></w:r></w:p>"));
        assertEquals("", DocxDocumentParser.docxTextBoxText(""));
        assertEquals("", DocxDocumentParser.docxTextBoxText(null));
    }

    /** 文本框内多段落：段落文本按出现顺序拼接（本实现只取 w:t，段落分隔不计） */
    @Test
    void multiParagraphTextBoxShouldKeepBothRuns() {
        String xml = "<w:txbxContent><w:p><w:r><w:t>一</w:t></w:r></w:p><w:p><w:r><w:t>二</w:t></w:r></w:p>"
                + "</w:txbxContent>";

        assertEquals("一二", DocxDocumentParser.docxTextBoxText(xml));
    }
}
