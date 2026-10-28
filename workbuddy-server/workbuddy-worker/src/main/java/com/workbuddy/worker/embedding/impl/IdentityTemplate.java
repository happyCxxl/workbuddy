package com.workbuddy.worker.embedding.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.worker.embedding.template.InputTemplatePort;
import org.springframework.stereotype.Component;

/**
 * 原样模板（唯一实现）：inputText = chunk.content。
 */
@Component
public class IdentityTemplate implements InputTemplatePort {

    /**
     * 模板渲染：把占位符替换为切片文本。
     *
     * @param template 模板串
     * @param chunk    切片
     * @return 渲染结果
     */
    @Override
    public String render(String template, Chunk chunk) {
        return chunk.getContent();
    }
}
