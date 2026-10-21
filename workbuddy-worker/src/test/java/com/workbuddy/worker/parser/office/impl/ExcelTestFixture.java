package com.workbuddy.worker.parser.office.impl;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import com.workbuddy.worker.testkit.PropertiesFixtures;

/**
 * Excel 解析测试共享夹具：被测解析器、解析上下文与工作簿字节化。
 */
final class ExcelTestFixture {

    static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String MIME_XLS = "application/vnd.ms-excel";

    private final ExcelDocumentParser parser = new ExcelDocumentParser();

    /**
     * 构造解析上下文。
     *
     * @param data     工作簿字节
     * @param mimeType 媒体类型
     * @return 解析上下文
     */
    ParseContext context(byte[] data, String mimeType) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("t");
        fileRef.setMimeType(mimeType);
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(PropertiesFixtures.parse());
        return context;
    }

    /**
     * 解析工作簿字节。
     *
     * @param data     工作簿字节
     * @param mimeType 媒体类型
     * @return 解析结果
     */
    ParseSource parse(byte[] data, String mimeType) {
        return parser.parse(context(data, mimeType));
    }

    /**
     * 保存工作簿为字节。
     *
     * @param workbook 工作簿
     * @return 工作簿字节
     * @throws IOException 保存失败
     */
    static byte[] save(Workbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }

    /**
     * 全部表格单元格文本。
     *
     * @param source 解析结果
     * @return 单元格文本
     */
    static List<String> cellTexts(ParseSource source) {
        return source.getElements().stream()
                .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                .flatMap(e -> e.getCells().stream())
                .map(ParseElement::getText).toList();
    }

    /**
     * 被测解析器。
     *
     * @return 解析器
     */
    ExcelDocumentParser parser() {
        return parser;
    }
}
