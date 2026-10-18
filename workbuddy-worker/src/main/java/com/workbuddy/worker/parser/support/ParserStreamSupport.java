package com.workbuddy.worker.parser.support;

import com.workbuddy.worker.parser.ParseContext;

import java.io.IOException;

/**
 * 解析输入流公共口径（PDF 与 Office 解析器共用）：全量读入内存
 * （POI/PDFBox 均需全量加载）；失败抛运行时异常由管线记 PARSE_CORRUPTED。
 */
public final class ParserStreamSupport {

    private ParserStreamSupport() {
    }

    /** 输入流全量读入内存；失败抛运行时异常由管线记 PARSE_CORRUPTED。 */
    public static byte[] readAll(ParseContext context) {
        try {
            return context.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取文件流失败: " + e.getMessage(), e);
        }
    }
}
