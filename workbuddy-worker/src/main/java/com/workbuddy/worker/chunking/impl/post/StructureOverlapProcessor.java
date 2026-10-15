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
 * 结构切片重叠（structureOverlap，默认 0）：相邻 BODY/TABLE 片的前缀式重叠——本片 content 前缀上一片末尾 N 字符。
 * 缓解片边界语义断裂；跨章节不生效（管线按章列表调用）；0=不处理（现状口径）。
 *
 * @author cxxl
 */
@Order(1)
@Component
public class StructureOverlapProcessor implements ChunkPostProcessor {

    @Override
    public void process(List<Chunk> chunks, SliceContext context) {
        int overlap = context.getStrategy().pipelineInt(PipelineKey.STRUCTURE_OVERLAP, 0);
        if (overlap <= 0 || chunks.size() < 2) {
            return;
        }
        Chunk previous = null;
        for (Chunk chunk : chunks) {
            if (NullUtil.isNotNull(previous) && appliesTo(chunk)) {
                String tail = previous.getContent().substring(Math.max(0, previous.getContent().length() - overlap));
                if (!tail.isBlank()) {
                    chunk.setContent(tail + "\n" + chunk.getContent());
                    chunk.setCharCount(chunk.getContent().length());
                }
            }
            if (appliesTo(chunk)) {
                previous = chunk;
            }
        }
    }

    /** 只作用于正文/表格片（图片占位片与父片不参与） */
    private boolean appliesTo(Chunk chunk) {
        String type = chunk.getContentType();
        return ChunkContentType.PARAGRAPH.name().equals(type)
                || ChunkContentType.FALLBACK.name().equals(type)
                || ChunkContentType.TABLE.name().equals(type);
    }
}
