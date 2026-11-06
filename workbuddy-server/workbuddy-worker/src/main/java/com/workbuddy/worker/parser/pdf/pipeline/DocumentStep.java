package com.workbuddy.worker.parser.pdf.pipeline;

import java.io.IOException;

/**
 * 文档步骤契约：一个步骤只做一次转换，输入输出类型由步骤声明。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface DocumentStep<I, O> {

    /**
     * 执行步骤。
     *
     * @param input 步骤输入
     * @return 步骤输出
     * @throws IOException 读取文档或写入临时产物失败
     */
    O execute(I input) throws IOException;
}
