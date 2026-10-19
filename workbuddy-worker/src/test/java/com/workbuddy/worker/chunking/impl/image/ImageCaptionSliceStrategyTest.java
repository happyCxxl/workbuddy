package com.workbuddy.worker.chunking.impl.image;

import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片切片器单测：图注占位内容 + 图注关联；无图注降级。
 */
class ImageCaptionSliceStrategyTest {

    private final ImageCaptionSliceStrategy strategy = new ImageCaptionSliceStrategy();

    private SliceContext context(String caption) {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        ChunkProperties props = new ChunkProperties();
        context.setStrategy(StrategyFixtures.chunkDefaults(props));
        context.setTitlePath(List.of("第一章", "1.2 附图"));
        UnifiedElement unified = new UnifiedElement();
        unified.setId("i-1");
        unified.setType(UnifiedElementType.IMAGE.name());
        unified.setCaption(caption);
        Map<String, UnifiedElement> byId = new HashMap<>();
        byId.put("i-1", unified);
        context.setById(byId);
        return context;
    }

    private ViewElement imageElement() {
        ViewElement element = new ViewElement();
        element.setElementId("i-1");
        element.setType(UnifiedElementType.IMAGE.name());
        element.setPage(3);
        element.setStatus(ViewElementStatus.NORMAL.name());
        return element;
    }

    /**
     * 守护 {@code captionShouldBeAttachedToPlaceholder}：该场景行为改动即失败。
     */
    @Test
    void captionShouldBeAttachedToPlaceholder() {
        Chunk chunk = strategy.slice(imageElement(), context("附件二：资质证书（扫描件）")).getFirst();

        assertEquals(ChunkContentType.IMAGE.name(), chunk.getContentType());
        assertTrue(chunk.getContent().contains("图片内文字未识别"));
        assertTrue(chunk.getContent().contains("图注：附件二：资质证书（扫描件）"));
        assertEquals(List.of("i-1"), chunk.getSourceElementIds());
        assertEquals(List.of(3), chunk.getPageRange());
        assertEquals("第一章 > 1.2 附图", chunk.getTitlePath());
    }

    /**
     * 守护 {@code noCaptionShouldFallbackToPlaceholderOnly}：该场景行为改动即失败。
     */
    @Test
    void noCaptionShouldFallbackToPlaceholderOnly() {
        Chunk chunk = strategy.slice(imageElement(), context(null)).getFirst();

        assertTrue(chunk.getContent().contains("图片内文字未识别"));
        assertFalse(chunk.getContent().contains("图注"));
    }
}
