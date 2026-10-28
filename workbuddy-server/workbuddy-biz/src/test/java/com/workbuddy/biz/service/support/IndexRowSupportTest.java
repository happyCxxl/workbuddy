package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.biz.service.support.index.IndexRowSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 索引行装配器单测。
 *
 * <p>守护 {@code assemble} 的三条口径：只取 SUCCESS/CACHED 记录、
 * 标题路径与来源元素自切片产物按 chunkId 关联、产物缺失返回 null 而不是空列表。
 * 产物存储为系统边界，用替身。
 */
@ExtendWith(MockitoExtension.class)
class IndexRowSupportTest {

    @Mock
    private FileStorage fileStorage;

    private IndexRowSupport assembler;

    /**
     * 每个用例重建装配器。
     */
    @BeforeEach
    void setUp() {
        assembler = new IndexRowSupport(fileStorage);
    }

    /**
     * 装配只取 SUCCESS/CACHED 记录，标题路径与来源元素按 chunkId 关联切片产物。
     *
     * <p>守护 {@code assemble} 的筛选与关联：把 FAILED 也收进来、或关联错位即失败。
     */
    @Test
    void assembleShouldFilterAndJoinWithChunks() {
        EmbeddingSet embedSet = new EmbeddingSet();
        var secondRecord = record("c2", "CACHED", "PARAGRAPH", "正文二", 0.2f);
        secondRecord.setParentChunkId("c1");
        embedSet.setRecords(List.of(
                record("c1", "SUCCESS", "PARAGRAPH", "正文一", 0.1f),
                secondRecord,
                record("c3", "FAILED", "PARAGRAPH", "正文三", 0.3f)));
        ChunkSet chunkSet = new ChunkSet();
        Chunk withMeta = new Chunk();
        withMeta.setChunkId("c1");
        withMeta.setTitlePath("第一章 > 第一节");
        withMeta.setSourceElementIds(List.of("e1", "e2"));
        Chunk bare = new Chunk();
        bare.setChunkId("c2");
        chunkSet.setChunks(List.of(withMeta, bare));

        when(fileStorage.getObject(any())).thenAnswer(invocation -> switch (invocation.getArgument(
                0, com.workbuddy.common.domain.storage.ObjectRef.class).objectKey()) {
            case "embed-artifact" -> bytes(embedSet);
            case "chunk-artifact" -> bytes(chunkSet);
            default -> null;
        });

        List<IndexRow> rows = assembler.assemble(9L, "kb-1", chunkRow(), embedRow());

        assertEquals(2, rows.size());
        IndexRow first = rows.get(0);
        assertEquals("c1", first.getChunkId());
        assertEquals(9L, first.getDocumentId());
        assertEquals("kb-1", first.getOwner());
        assertEquals("PARAGRAPH", first.getContentType());
        assertNull(first.getParentChunkId());
        assertEquals("正文一", first.getContent());
        assertEquals("第一章 > 第一节", first.getTitlePath());
        assertEquals("[\"e1\",\"e2\"]", first.getSourceElementIds());
        assertEquals(List.of(0.1f), first.getVector());
        IndexRow second = rows.get(1);
        assertEquals("c2", second.getChunkId());
        assertEquals(List.of(0.2f), second.getVector());
        assertEquals("c1", second.getParentChunkId());
        assertEquals("c1", second.getParentChunkId());
        assertNull(second.getTitlePath());
        // 无来源元素的切片序列化为空数组（源码字段默认空列表，非 null）
        assertEquals("[]", second.getSourceElementIds());
    }

    /**
     * 向量产物缺失或解析失败时返回 null（追加路径跳过该文件，不当作空文件清空索引）。
     *
     * <p>守护 {@code assemble} 的缺失口径：把 null 改成空列表即失败（增量构建会误删已有索引）。
     */
    @Test
    void assembleShouldReturnNullWhenEmbedArtifactMissing() {
        when(fileStorage.getObject(any())).thenReturn(null);

        assertNull(assembler.assemble(9L, "kb-1", chunkRow(), embedRow()));
    }

    /**
     * 记录全为 FAILED 时返回空列表（文件有效但无内容可索引）。
     *
     * <p>守护 {@code assemble} 的空列表口径：与缺失 null 区分开。
     */
    @Test
    void assembleShouldReturnEmptyWhenNoEligibleRecords() {
        EmbeddingSet embedSet = new EmbeddingSet();
        embedSet.setRecords(List.of(record("c1", "FAILED", "PARAGRAPH", "正文", 0.1f)));
        when(fileStorage.getObject(any())).thenReturn(bytes(embedSet));

        List<IndexRow> rows = assembler.assemble(9L, "kb-1", chunkRow(), embedRow());

        assertEquals(0, rows.size());
    }

    /**
     * 切片产物缺失时标题路径与来源元素留空，行照常装配。
     *
     * <p>守护 {@code readChunkIndex} 的缺失容忍：切片产物读不到不该连向量行一起丢。
     */
    @Test
    void assembleShouldTolerateMissingChunkArtifact() {
        EmbeddingSet embedSet = new EmbeddingSet();
        embedSet.setRecords(List.of(record("c1", "SUCCESS", "PARAGRAPH", "正文一", 0.1f)));
        when(fileStorage.getObject(any())).thenReturn(bytes(embedSet));

        List<IndexRow> rows = assembler.assemble(9L, "kb-1", chunkRow(), embedRow());

        assertEquals(1, rows.size());
        assertNull(rows.get(0).getTitlePath());
    }

    /**
     * 切片产物损坏（非法 JSON）时按缺失处理。
     *
     * <p>守护 {@code readChunkIndex} 的解析兜底：解析异常上抛即失败。
     */
    @Test
    void readChunkIndexShouldTolerateCorruptedArtifact() {
        when(fileStorage.getObject(any())).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));

        assertNull(assembler.readChunkIndex(chunkRowRef()));
    }

    /**
     * 向量产物损坏（非法 JSON）时按缺失处理。
     *
     * <p>守护 {@code readEmbeddingSet} 的解析兜底。
     */
    @Test
    void readEmbeddingSetShouldTolerateCorruptedArtifact() {
        when(fileStorage.getObject(any())).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));

        assertNull(assembler.readEmbeddingSet(embedRowRef()));
    }

    /**
     * 构造向量记录。
     *
     * @param chunkId     切片 ID
     * @param status      状态
     * @param contentType 内容类型
     * @param inputText   输入文本
     * @param firstValue  向量首维
     * @return 向量记录
     */
    private EmbeddingRecord record(String chunkId, String status, String contentType, String inputText,
                                   float firstValue) {
        EmbeddingRecord record = new EmbeddingRecord();
        record.setChunkId(chunkId);
        record.setStatus(status);
        record.setContentType(contentType);
        record.setInputText(inputText);
        record.setVector(List.of(firstValue));
        return record;
    }

    /**
     * 序列化为产物字节。
     *
     * @param value 待序列化对象
     * @return 字节
     */
    private byte[] bytes(Object value) {
        return JsonUtil.toJsonStr(value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造切片集合行（产物键 chunk-artifact）。
     *
     * @return 切片集合行
     */
    private WbChunkSet chunkRow() {
        WbChunkSet row = new WbChunkSet();
        row.setArtifactId("chunk-artifact");
        row.setStorageType("local");
        row.setStorageSourceId(5L);
        row.setBucket("artifacts");
        return row;
    }

    /**
     * 构造向量集合行（产物键 embed-artifact）。
     *
     * @return 向量集合行
     */
    private WbEmbeddingSet embedRow() {
        WbEmbeddingSet row = new WbEmbeddingSet();
        row.setArtifactId("embed-artifact");
        row.setStorageType("local");
        row.setStorageSourceId(5L);
        row.setBucket("artifacts");
        return row;
    }

    /**
     * 向量集合行对应产物位置。
     *
     * @return 对象位置
     */
    private com.workbuddy.common.domain.storage.ObjectRef embedRowRef() {
        return com.workbuddy.common.domain.storage.ObjectRef.ofEmbeddingSet(embedRow());
    }

    /**
     * 切片集合行对应产物位置。
     *
     * @return 对象位置
     */
    private com.workbuddy.common.domain.storage.ObjectRef chunkRowRef() {
        return com.workbuddy.common.domain.storage.ObjectRef.ofChunkSet(chunkRow());
    }
}
