package com.workbuddy.worker.indexing.impl;

import com.workbuddy.infra.vector.CollectionRow;
import com.workbuddy.infra.vector.KnowledgeCollectionPort;
import com.workbuddy.infra.vector.ScoredRow;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.FullTextQuery;
import com.workbuddy.worker.indexing.search.VectorHit;
import com.workbuddy.worker.indexing.search.VectorQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Milvus 索引 Port 单测（薄壳适配器）：业务行映射 / 业务过滤拼装 / 命中映射 / 生命周期委托 /
 * 集合命名约定 / 主键查询与空输入。
 */
@ExtendWith(MockitoExtension.class)
class MilvusIndexPortImplTest {

    @Mock
    private KnowledgeCollectionPort collectionPort;

    private MilvusIndexPortImpl port;

    @BeforeEach
    void setUp() {
        port = new MilvusIndexPortImpl(collectionPort);
    }

    private IndexRow row() {
        IndexRow row = new IndexRow();
        row.setChunkId("c1");
        row.setDocumentId(10L);
        row.setOwner(null);
        row.setContentType("PARAGRAPH");
        row.setParentChunkId(null);
        row.setContent("hello");
        row.setTitlePath(null);
        row.setSourceElementIds(null);
        row.setVector(List.of(0.1F, 0.2F));
        return row;
    }

    private ScoredRow scoredRow() {
        ScoredRow row = new ScoredRow();
        row.setId("c1");
        row.setDocumentId(10L);
        row.setOwner("userA");
        row.setContentType("PARAGRAPH");
        row.setContent("hello");
        row.setTitlePath("T");
        row.setParentChunkId("p1");
        row.setSourceElementIds("[\"e1\"]");
        row.setScore(0.87F);
        return row;
    }

    /**
     * 守护 {@code appendShouldUpsertMappedRows}：该场景行为改动即失败。
     */
    @Test
    void appendShouldUpsertMappedRows() {
        port.append("kb_1_v1", List.of(row()));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<CollectionRow>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(collectionPort).upsert(eq("kb_1_v1"), captor.capture());
        CollectionRow mapped = captor.getValue().getFirst();
        assertEquals("c1", mapped.getId());
        assertEquals(10L, mapped.getDocumentId());
        assertEquals("PARAGRAPH", mapped.getContentType());
        assertEquals("hello", mapped.getContent());
        assertEquals(2, mapped.getVector().size());
    }

    /**
     * 守护 {@code searchVectorShouldMapHitsAndFilterBusinessOnly}：该场景行为改动即失败。
     */
    @Test
    void searchVectorShouldMapHitsAndFilterBusinessOnly() {
        when(collectionPort.searchVector(eq("kb_1_v1"), anyList(), eq(5), any()))
                .thenReturn(List.of(scoredRow()));

        VectorQuery query = VectorQuery.builder().vector(List.of(0.1F)).topK(5)
                .documentId(10L).owner("userA").contentType("PARAGRAPH").build();
        List<VectorHit> hits = port.searchVector("kb_1_v1", query);

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(collectionPort).searchVector(eq("kb_1_v1"), anyList(), eq(5), filterCaptor.capture());
        String filter = filterCaptor.getValue();
        assertTrue(filter.contains("document_id == 10"), filter);
        assertTrue(filter.contains("owner == 'userA'"), filter);
        assertTrue(filter.contains("content_type == 'PARAGRAPH'"), filter);

        assertEquals(1, hits.size());
        assertEquals("c1", hits.getFirst().getChunkId());
        assertEquals(0.87F, hits.getFirst().getScore());
        assertEquals(10L, hits.getFirst().getDocumentId());
        assertEquals("hello", hits.getFirst().getContent());
    }

    /**
     * 守护 {@code searchFullTextShouldPassKeywordAndLimit}：该场景行为改动即失败。
     */
    @Test
    void searchFullTextShouldPassKeywordAndLimit() {
        when(collectionPort.searchFullText(eq("kb_1_v1"), eq("单引号"), eq(5L), any()))
                .thenReturn(List.of());

        List<FullTextHit> hits = port.searchFullText("kb_1_v1",
                FullTextQuery.builder().keyword("单引号").limit(5).documentId(10L).build());

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(collectionPort).searchFullText(eq("kb_1_v1"), eq("单引号"), eq(5L), filterCaptor.capture());
        assertTrue(filterCaptor.getValue().contains("document_id == 10"));
        assertTrue(hits.isEmpty());
    }

    /**
     * 守护 {@code listChunkIdsShouldDelegateWithFilters}：该场景行为改动即失败。
     */
    @Test
    void listChunkIdsShouldDelegateWithFilters() {
        when(collectionPort.listIds(eq("kb_1_v1"), any())).thenReturn(List.of("c1"));

        assertEquals(List.of("c1"), port.listChunkIds("kb_1_v1"));
        verify(collectionPort).listIds("kb_1_v1", "id != ''");

        port.listChunkIds("kb_1_v1", 10L);
        verify(collectionPort).listIds("kb_1_v1", "document_id == 10");
    }

    /**
     * 守护 {@code lifecycleShouldDelegate}：该场景行为改动即失败。
     */
    @Test
    void lifecycleShouldDelegate() {
        port.ensureCollection("kb_1_v1", 1024);
        verify(collectionPort).ensureCollection("kb_1_v1", 1024);
        port.load("kb_1_v1");
        verify(collectionPort).load("kb_1_v1");
        port.drop("kb_1_v1");
        verify(collectionPort).drop("kb_1_v1");
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(collectionPort).getInvocations().stream()
                .anyMatch(inv -> "drop".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code collectionNameShouldFollowConvention}：该场景行为改动即失败。
     */
    @Test
    void collectionNameShouldFollowConvention() {
        assertEquals("kb_12_v3", MilvusIndexPort.collectionName(12L, "v3"));
    }

    /**
     * 守护 {@code queryChunkByIdsShouldMapRows}：该场景行为改动即失败。
     */
    @Test
    void queryChunkByIdsShouldMapRows() {
        CollectionRow row = new CollectionRow();
        row.setId("parent-1");
        row.setDocumentId(10L);
        row.setContent("第三节 保证金全文");
        when(collectionPort.queryByIds("kb_1_v1", List.of("parent-1"))).thenReturn(List.of(row));

        List<IndexRow> rows = port.queryChunkByIds("kb_1_v1", List.of("parent-1"));

        assertEquals(1, rows.size());
        assertEquals("parent-1", rows.getFirst().getChunkId());
        assertEquals("第三节 保证金全文", rows.getFirst().getContent());
    }

    /**
     * 守护 {@code queryChunkByIdsShouldReturnEmptyForEmptyInput}：该场景行为改动即失败。
     */
    @Test
    void queryChunkByIdsShouldReturnEmptyForEmptyInput() {
        assertTrue(port.queryChunkByIds("kb_1_v1", List.of()).isEmpty());
        verify(collectionPort, never()).queryByIds(any(), any());
    }

    /**
     * 守护 {@code hitMappingShouldTolerateNulls}：该场景行为改动即失败。
     */
    @Test
    void hitMappingShouldTolerateNulls() {
        ScoredRow sparse = new ScoredRow();
        sparse.setId("c1");
        sparse.setDocumentId(10L);
        sparse.setScore(null);
        when(collectionPort.searchVector(eq("kb_1_v1"), anyList(), eq(5), any())).thenReturn(List.of(sparse));

        VectorHit hit = port.searchVector("kb_1_v1", VectorQuery.builder().vector(List.of(0.1F)).topK(5).build())
                .getFirst();
        assertNull(hit.getContent());
        assertNull(hit.getScore());
        assertEquals("c1", hit.getChunkId());
    }
}
