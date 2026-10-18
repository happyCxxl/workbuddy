package com.workbuddy.worker.chunking.impl.post;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.worker.chunking.slice.ChunkPostProcessor;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 标题入正文（titleInContent，默认 ON，对齐开源 LangChain strip_headers=false 口径）：
 * 非父片 content 首行拼接标题链（titlePath + "\n" + 原内容），charCount 重算。
 * 父片=子片拼接（子片已带标题），不加；OFF = 标题只在 titlePath 元数据。
 */
@Order(3)
@Component
public class TitleInContentProcessor implements ChunkPostProcessor {

    @Override
    public void process(List<Chunk> chunks, SliceContext context) {
        if (!context.getStrategy().pipelineOn(PipelineKey.TITLE_IN_CONTENT)) {
            return;
        }
        for (Chunk chunk : chunks) {
            if (ChunkContentType.SECTION.name().equals(chunk.getContentType())) {
                continue;
            }
            if (NullUtil.isNull(chunk.getTitlePath()) || chunk.getTitlePath().isBlank()) {
                continue;
            }
            chunk.setContent(chunk.getTitlePath() + "\n" + chunk.getContent());
            chunk.setCharCount(chunk.getContent().length());
        }
    }
}
