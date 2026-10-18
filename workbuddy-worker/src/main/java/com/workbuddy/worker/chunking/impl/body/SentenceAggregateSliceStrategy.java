package com.workbuddy.worker.chunking.impl.body;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.strategy.ChunkParamKeys;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文切片器（句子聚合）：跨元素按句聚合（句边界与兜底同口径），累计 ≥ targetMaxLen 结算；
 * 超长单句先结算缓冲再走兜底降级（FALLBACK）。每句以合成元素入正文缓冲（复用 SliceContext.bodyBuffer）。
 */
@Component
public class SentenceAggregateSliceStrategy extends AbstractBodySliceStrategy {

    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.BODY_SENTENCE_AGGREGATE;
    }

    @Override
    protected List<Chunk> sliceText(String text, ViewElement element, SliceContext context) {
        ChunkRouteConfig bodyConfig = context.getStrategy().route(ChunkRoute.BODY);
        int targetMaxLen = bodyConfig.intParam(ChunkParamKeys.TARGET_MAX_LEN, 800);
        int softMaxLen = bodyConfig.intParam(ChunkParamKeys.SOFT_MAX_LEN, 1000);
        List<Chunk> emitted = new ArrayList<>();

        for (String sentence : BodyChunkSupport.splitSentences(text)) {
            if (sentence.length() > softMaxLen) {
                if (!context.getBodyBuffer().isEmpty()) {
                    emitted.add(flushGroup(context));
                }
                emitted.addAll(fallbackChunks(sentence, element, context, "超长句子递归降级"));
            } else {
                ViewElement synthetic = new ViewElement();
                synthetic.setElementId(element.getElementId());
                synthetic.setType(element.getType());
                synthetic.setPage(element.getPage());
                synthetic.setNormalizedText(sentence);
                context.getBodyBuffer().add(synthetic);
                if (BodyChunkSupport.bufferLength(context.getBodyBuffer()) >= targetMaxLen) {
                    emitted.add(flushGroup(context));
                }
            }
        }
        return emitted;
    }

    @Override
    protected Chunk flushGroup(SliceContext context) {
        BodyChunkSupport.BodyBuffer settled = BodyChunkSupport.settleBuffer(context);
        return BodyChunkSupport.buildChunk(ChunkContentType.PARAGRAPH.name(), settled.text(), context,
                settled.elementIds(), settled.pages());
    }
}
