package com.workbuddy.worker.parser.pdf.extract.pipeline;

import java.io.IOException;

/**
 * 单页提取步骤契约：按固定顺序读写 {@link ExtractionContext}。
 */
public interface ExtractionStep {

    /**
     * 稳定步骤名。
     *
     * @return 步骤名
     */
    String name();

    /**
     * 执行本步骤。
     *
     * @param context 单页提取工作区
     * @throws IOException 读取 PDF 页面失败
     */
    void apply(ExtractionContext context) throws IOException;
}
