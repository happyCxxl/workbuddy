package com.workbuddy.worker.parser.pdf.extract.pipeline;

import com.workbuddy.worker.parser.pdf.extract.PdfTokenSupport;

import java.io.IOException;

/**
 * PDFBox token 提取：字符、坐标、字体与未映射字体集合。
 */
public final class TokenExtractStep implements ExtractionStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "token-extract";
    }

    /**
     * 提取页面 token。
     *
     * @param context 单页提取工作区
     * @throws IOException 读取页面内容失败
     */
    @Override
    public void apply(ExtractionContext context) throws IOException {
        context.setStripped(PdfTokenSupport.strip(context.document(), context.pageNo()));
    }
}
