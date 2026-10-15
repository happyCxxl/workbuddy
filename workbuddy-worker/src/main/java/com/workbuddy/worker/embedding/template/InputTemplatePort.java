package com.workbuddy.worker.embedding.template;

import com.workbuddy.common.domain.chunk.Chunk;

/**
 * 输入模板渲染 Port（向量化扩展接口）：按策略模板把切片渲染成编码输入文本。
 * 唯一实现 {@link com.workbuddy.worker.embedding.impl.IdentityTemplate}（原样编码，
 * 行为零改动——实验对比语义由上游切片策略保证）；类型化模板（评分标准/报价表等）将来只新增实现类。
 *
 * @author cxxl
 */
public interface InputTemplatePort {

    /**
     * 渲染编码输入文本。
     *
     * @param template 策略模板串（如 "{content}"；当前不应用）
     * @param chunk    切片（content/titlePath/contentType/tableRef 等模板变量来源）
     * @return 编码输入文本
     */
    String render(String template, Chunk chunk);
}
