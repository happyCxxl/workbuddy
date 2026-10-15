package com.workbuddy.worker.embedding.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.worker.embedding.template.InputTemplatePort;
import org.springframework.stereotype.Component;

/**
 * 原样模板（唯一实现）：inputText = chunk.content。
 *
 * @author cxxl
 */
@Component
public class IdentityTemplate implements InputTemplatePort {

    @Override
    public String render(String template, Chunk chunk) {
        return chunk.getContent();
    }
}
