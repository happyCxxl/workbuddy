package com.workbuddy.worker.chunking.impl.body;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.slice.SliceStrategy;
import com.workbuddy.worker.chunking.WindowSlicer;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.strategy.ChunkParamKeys;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文切片器（固定窗口 + 重叠）：忽略语义边界，flush 时对缓冲文本按 len 窗口切、相邻窗口重叠 overlap。
 * contentType=PARAGRAPH（能力名与策略版本区分口径）；评测基线用途。
 *
 * @author cxxl
 */
@Component
public class FixedWindowSliceStrategy implements SliceStrategy {

    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.BODY_FIXED_WINDOW;
    }

    @Override
    public List<Chunk> slice(ViewElement element, SliceContext context) {
        if (StrUtil.isBlank(element.getNormalizedText())) {
            return List.of();
        }
        // 只缓冲整元素文本，窗口切统一在 flush 点进行
        ViewElement synthetic = new ViewElement();
        synthetic.setElementId(element.getElementId());
        synthetic.setType(element.getType());
        synthetic.setPage(element.getPage());
        synthetic.setNormalizedText(element.getNormalizedText());
        context.getBodyBuffer().add(synthetic);
        return List.of();
    }

    @Override
    public List<Chunk> flush(SliceContext context) {
        if (context.getBodyBuffer().isEmpty()) {
            return List.of();
        }
        ChunkRouteConfig bodyConfig = context.getStrategy().route(ChunkRoute.BODY);
        int len = bodyConfig.intParam(ChunkParamKeys.LEN, 500);
        int overlap = bodyConfig.intParam(ChunkParamKeys.OVERLAP, 50);

        BodyChunkSupport.BodyBuffer settled = BodyChunkSupport.settleBuffer(context);
        List<Chunk> chunks = new ArrayList<>();
        for (String piece : WindowSlicer.slice(settled.text(), len, overlap)) {
            chunks.add(BodyChunkSupport.buildChunk(ChunkContentType.PARAGRAPH.name(), piece, context,
                    settled.elementIds(), settled.pages()));
        }
        return chunks;
    }
}
