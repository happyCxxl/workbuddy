package com.workbuddy.worker.chunking.impl.post;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.worker.chunking.slice.ChunkPostProcessor;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 碎片合并（minMergeLen，默认 300）：正文碎片（< minMergeLen）并入同章前一个正文片，避免"一句话一片"。
 * 只作用于正文片；仅与"紧邻的前一个正文片"合并（不跨表格/图片，保持正文组连续口径）；
 * 跨章节不生效；无相邻上一正文片则保留原样。
 */
@Order(2)
@Component
public class MinMergeProcessor implements ChunkPostProcessor {

    /**
     * 就地处理切片列表（只改传入的列表元素，不返回新集合）。
     *
     * @param chunks  切片列表
     * @param context 切片上下文
     */
    @Override
    public void process(List<Chunk> chunks, SliceContext context) {
        int minMergeLen = context.getStrategy().pipelineInt(PipelineKey.MIN_MERGE_LEN, 300);
        if (minMergeLen <= 0 || chunks.isEmpty()) {
            return;
        }
        Chunk lastBody = null;
        int lastBodyIndex = -1;
        List<Chunk> kept = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (isBody(chunk)) {
                // 仅与"紧邻的前一个正文片"合并（不跨表格/图片，保持正文组连续口径）
                if (chunk.getCharCount() < minMergeLen && NullUtil.isNotNull(lastBody)
                        && lastBodyIndex == kept.size() - 1) {
                    mergeInto(lastBody, chunk);
                    continue;
                }
                lastBody = chunk;
                lastBodyIndex = kept.size();
            }
            kept.add(chunk);
        }
        chunks.clear();
        chunks.addAll(kept);
    }

    private boolean isBody(Chunk chunk) {
        String type = chunk.getContentType();
        return ChunkContentType.PARAGRAPH.name().equals(type)
                || ChunkContentType.FALLBACK.name().equals(type);
    }

    private void mergeInto(Chunk target, Chunk fragment) {
        target.setContent(target.getContent() + "\n" + fragment.getContent());
        target.setCharCount(target.getContent().length());
        if (NullUtil.isNotNull(fragment.getSourceElementIds())) {
            List<String> ids = new ArrayList<>(NullUtil.isNull(target.getSourceElementIds())
                    ? List.of() : target.getSourceElementIds());
            ids.addAll(fragment.getSourceElementIds());
            target.setSourceElementIds(ids);
        }
        Set<Integer> pages = new HashSet<>();
        if (NullUtil.isNotNull(target.getPageRange())) {
            pages.addAll(target.getPageRange());
        }
        if (NullUtil.isNotNull(fragment.getPageRange())) {
            pages.addAll(fragment.getPageRange());
        }
        target.setPageRange(pages.isEmpty() ? null : pages.stream().sorted().toList());
    }
}
