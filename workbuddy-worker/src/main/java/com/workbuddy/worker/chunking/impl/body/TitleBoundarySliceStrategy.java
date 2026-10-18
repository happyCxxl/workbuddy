package com.workbuddy.worker.chunking.impl.body;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.slice.SliceStrategy;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.strategy.ChunkParamKeys;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 正文切片器（标题边界，一节一片）：缓冲本节全部文本元素，只在 TITLE 边界与文档末尾结算（管线 flush 规则
 * 保证表格/图片不断片）；结算时文本 ≤ maxLen 成一片（PARAGRAPH），超过则走所选兜底算法降级（FALLBACK）。
 *
 * @author cxxl
 */
@Component
public class TitleBoundarySliceStrategy implements SliceStrategy {

    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.BODY_TITLE_BOUNDARY;
    }

    @Override
    public List<Chunk> slice(ViewElement element, SliceContext context) {
        if (StrUtil.isBlank(element.getNormalizedText())) {
            return List.of();
        }
        // 只缓冲，不逐元素结算（一节一片在 flush 统一产出）
        context.getBodyBuffer().add(element);
        return List.of();
    }

    @Override
    public List<Chunk> flush(SliceContext context) {
        List<ViewElement> buffer = context.getBodyBuffer();
        if (buffer.isEmpty()) {
            return List.of();
        }
        int maxLen = context.getStrategy().route(ChunkRoute.BODY).intParam(ChunkParamKeys.MAX_LEN, 3000);
        String content = String.join("\n", buffer.stream().map(ViewElement::getNormalizedText)
                .filter(StrUtil::isNotBlank).toList());
        List<String> ids = buffer.stream().map(ViewElement::getElementId).distinct().toList();
        List<Integer> pages = BodyChunkSupport.mergePages(buffer);
        buffer.clear();

        if (content.length() <= maxLen) {
            return List.of(BodyChunkSupport.buildChunk(
                    ChunkContentType.PARAGRAPH.name(), content, context, ids, pages));
        }
        // 超长节：走兜底降级（与正文聚合类共用同一入口，每片合法、带 fallbackReason）
        return BodyChunkSupport.fallbackChunks(content, ids, pages, context,
                BodyChunkSupport.REASON_TITLE_BOUNDARY_TOO_LONG);
    }
}
