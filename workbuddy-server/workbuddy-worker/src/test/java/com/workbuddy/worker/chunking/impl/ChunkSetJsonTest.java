package com.workbuddy.worker.chunking.impl;
import com.workbuddy.common.enums.chunk.ChunkContentType;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ChunkSet JSON 往返单测（schema 只增不删的序列化基线）。
 */
class ChunkSetJsonTest {

    /**
     * 守护 {@code chunkSetShouldRoundTrip}：该场景行为改动即失败。
     */
    @Test
    void chunkSetShouldRoundTrip() {
        ChunkSet chunkSet = new ChunkSet();
        chunkSet.setChunkSetId("cs-doc-1-chunk-hybrid-v1");
        chunkSet.setFileResultId(10L);
        chunkSet.setDocumentId("doc-1");
        chunkSet.setStrategyVersion("chunk-hybrid-v1");
        chunkSet.setUpstreamProductRef(50L);
        chunkSet.setChunkCount(2);

        Chunk parent = new Chunk();
        parent.setChunkId("chunk-0001");
        parent.setContent("第一章 总则整节内容");
        parent.setContentType(ChunkContentType.SECTION.name());
        parent.setTitlePath("第一章 总则");
        parent.setSourceElementIds(List.of("n-1", "n-2"));
        parent.setPageRange(List.of(1));
        parent.setOrder(1);
        parent.setCharCount(10);
        parent.setTokenCount(7);

        Chunk child = new Chunk();
        child.setChunkId("chunk-0002");
        child.setParentChunkId("chunk-0001");
        child.setContent("投标保证金为人民币叁佰万元整。");
        child.setContentType(ChunkContentType.PARAGRAPH.name());
        child.setTitlePath("第一章 总则");
        child.setSourceElementIds(List.of("n-1"));
        child.setPageRange(List.of(1));
        child.setOrder(2);
        child.setCharCount(16);
        child.setTokenCount(11);
        child.setFallbackReason(null);
        chunkSet.setChunks(List.of(parent, child));

        String json = JsonUtil.toJsonStr(chunkSet);
        ChunkSet restored = JsonUtil.toObject(json, ChunkSet.class);

        assertNotNull(restored);
        assertEquals("cs-doc-1-chunk-hybrid-v1", restored.getChunkSetId());
        assertEquals(10L, restored.getFileResultId());
        assertEquals(2, restored.getChunkCount());
        assertEquals("chunk-0002", restored.getChunks().get(1).getChunkId());
        assertEquals("chunk-0001", restored.getChunks().get(1).getParentChunkId());
        assertEquals(ChunkContentType.SECTION.name(), restored.getChunks().get(0).getContentType());
    }
}
