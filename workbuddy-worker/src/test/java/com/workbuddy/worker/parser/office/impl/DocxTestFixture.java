package com.workbuddy.worker.parser.office.impl;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * DOCX 解析测试共享夹具：被测解析器、解析上下文与文档字节化。
 */
final class DocxTestFixture {

    static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocxDocumentParser parser = new DocxDocumentParser();

    /**
     * 构造解析上下文。
     *
     * @param data DOCX 字节
     * @return 解析上下文
     */
    ParseContext context(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("t");
        fileRef.setMimeType(MIME_DOCX);
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return context;
    }

    /**
     * 解析 DOCX 字节。
     *
     * @param data DOCX 字节
     * @return 解析结果
     */
    ParseSource parse(byte[] data) {
        return parser.parse(context(data));
    }

    /**
     * 按给定上下文解析。
     *
     * @param context 解析上下文
     * @return 解析结果
     */
    ParseSource parse(ParseContext context) {
        return parser.parse(context);
    }

    /**
     * 保存文档为字节。
     *
     * @param doc DOCX 文档
     * @return 文档字节
     * @throws IOException 保存失败
     */
    static byte[] save(XWPFDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        return out.toByteArray();
    }

    /**
     * 造基础 DOCX：标题段 + 正文段 + 两行四列表（首行横向合并）。
     *
     * @return DOCX 字节
     * @throws IOException 写入失败
     */
    byte[] buildDocx() throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph title = doc.createParagraph();
            title.setStyle("Heading1");
            XWPFRun titleRun = title.createRun();
            titleRun.setText("第一章 投标人须知");
            titleRun.setBold(true);
            titleRun.setFontSize(16.0);
            XWPFParagraph body = doc.createParagraph();
            body.createRun().setText("投标保证金为人民币叁佰万元整。");

            XWPFTable table = doc.createTable(2, 4);
            XWPFTableRow header = table.getRow(0);
            header.getCell(0).setText("评分项");
            header.getCell(1).setText("评分标准");
            // (0,1) 横向合并两列：真合并只对应一个 tc，占位 tc 不存在，其后 (0,3) 仍是真实单元格
            header.getCell(1).getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.valueOf(2));
            header.removeCell(2);
            header.getCell(2).setText("备注");
            table.getRow(1).getCell(0).setText("A1 报价");
            table.getRow(1).getCell(1).setText("低于基准价 1% 以内得 30 分");
            table.getRow(1).getCell(2).setText("30");
            table.getRow(1).getCell(3).setText("评分表");
            return save(doc);
        }
    }

    /**
     * 被测解析器。
     *
     * @return 解析器
     */
    DocxDocumentParser parser() {
        return parser;
    }
}
