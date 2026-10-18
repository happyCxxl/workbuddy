package com.workbuddy.vector;

import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Milvus 知识库集合实现单测：行 upsert（schema 字段名/空值归一化）/ 向量检索（命中映射）/
 * 全文检索（EmbeddedText + 关键词清洗）/ 分页拉取 / 主键查询与空输入。
 */
@ExtendWith(MockitoExtension.class)
class MilvusKnowledgeCollectionTest {

    @Mock
    private MilvusClientV2 milvusClientV2;

    private MilvusKnowledgeCollection collection;

    @BeforeEach
    void setUp() {
        collection = new MilvusKnowledgeCollection(milvusClientV2);
    }

    private CollectionRow row() {
        CollectionRow row = new CollectionRow();
        row.setId("c1");
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

    @Test
    void upsertShouldBuildSchemaNamedJsonRows() {
        collection.upsert("kb_1_v1", List.of(row()));

        ArgumentCaptor<UpsertReq> captor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(milvusClientV2).upsert(captor.capture());
        assertEquals("kb_1_v1", captor.getValue().getCollectionName());
        JsonObject obj = captor.getValue().getData().getFirst();
        assertEquals("c1", obj.get("id").getAsString());
        assertEquals(10L, obj.get("document_id").getAsLong());
        assertEquals("", obj.get("owner").getAsString());
        assertEquals("", obj.get("title_path").getAsString());
        assertEquals("hello", obj.get("content").getAsString());
        assertEquals(2, obj.get("vector").getAsJsonArray().size());
    }

    @Test
    void upsertShouldSkipWhenEmpty() {
        collection.upsert("kb_1_v1", List.of());
        verify(milvusClientV2, never()).upsert(any());
    }

    @Test
    void searchVectorShouldMapHitsWithScore() {
        SearchResp.SearchResult result = mock(SearchResp.SearchResult.class);
        when(result.getEntity()).thenReturn(Map.of(
                "id", "c1", "document_id", 10L, "content_type", "PARAGRAPH",
                "content", "hello", "title_path", "T", "parent_chunk_id", "p1",
                "source_element_ids", "[\"e1\"]"));
        when(result.getScore()).thenReturn(0.87F);
        SearchResp resp = mock(SearchResp.class);
        when(resp.getSearchResults()).thenReturn(List.of(List.of(result)));
        when(milvusClientV2.search(any())).thenReturn(resp);

        List<ScoredRow> hits = collection.searchVector("kb_1_v1", List.of(0.1F), 5,
                "document_id == 10");

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(milvusClientV2).search(captor.capture());
        assertEquals("vector", captor.getValue().getAnnsField());
        assertInstanceOf(FloatVec.class, captor.getValue().getData().getFirst());
        assertEquals("document_id == 10", captor.getValue().getFilter());

        assertEquals(1, hits.size());
        assertEquals("c1", hits.getFirst().getId());
        assertEquals(0.87F, hits.getFirst().getScore());
        assertEquals("hello", hits.getFirst().getContent());
    }

    @Test
    void searchFullTextShouldUseEmbeddedTextAndSanitize() {
        SearchResp resp = mock(SearchResp.class);
        when(resp.getSearchResults()).thenReturn(List.of());
        when(milvusClientV2.search(any())).thenReturn(resp);

        collection.searchFullText("kb_1_v1", "单'引号\n换行", 5, "document_id == 10");

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(milvusClientV2).search(captor.capture());
        assertEquals("content_sparse", captor.getValue().getAnnsField());
        BaseVector data = captor.getValue().getData().getFirst();
        assertInstanceOf(EmbeddedText.class, data);
        assertEquals("单 引号 换行", data.getData());
    }

    @Test
    void listIdsShouldPaginate() {
        List<QueryResp.QueryResult> fullPage = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            QueryResp.QueryResult r = mock(QueryResp.QueryResult.class);
            when(r.getEntity()).thenReturn(Map.of("id", "c" + i));
            fullPage.add(r);
        }
        QueryResp page1 = mock(QueryResp.class);
        when(page1.getQueryResults()).thenReturn(fullPage);
        QueryResp.QueryResult tail = mock(QueryResp.QueryResult.class);
        when(tail.getEntity()).thenReturn(Map.of("id", "c1000"));
        QueryResp page2 = mock(QueryResp.class);
        when(page2.getQueryResults()).thenReturn(List.of(tail));
        when(milvusClientV2.query(any())).thenReturn(page1, page2);

        List<String> ids = collection.listIds("kb_1_v1", "id != ''");

        ArgumentCaptor<QueryReq> captor = ArgumentCaptor.forClass(QueryReq.class);
        verify(milvusClientV2, org.mockito.Mockito.times(2)).query(captor.capture());
        assertEquals(0L, captor.getAllValues().get(0).getOffset());
        assertEquals(1000L, captor.getAllValues().get(1).getOffset());
        assertEquals(1001, ids.size());
        assertEquals("c0", ids.getFirst());
        assertEquals("c1000", ids.get(1000));
    }

    @Test
    void queryByIdsShouldMapRowsAndTolerateNulls() {
        Map<String, Object> entity = new java.util.HashMap<>();
        entity.put("id", "parent-1");
        entity.put("document_id", 10L);
        entity.put("content", "第三节 保证金全文");
        QueryResp.QueryResult result = mock(QueryResp.QueryResult.class);
        when(result.getEntity()).thenReturn(entity);
        QueryResp resp = mock(QueryResp.class);
        when(resp.getQueryResults()).thenReturn(List.of(result));
        when(milvusClientV2.query(any(QueryReq.class))).thenReturn(resp);

        List<CollectionRow> rows = collection.queryByIds("kb_1_v1", List.of("parent-1"));

        assertEquals(1, rows.size());
        assertEquals("parent-1", rows.getFirst().getId());
        assertEquals("第三节 保证金全文", rows.getFirst().getContent());
        assertNull(rows.getFirst().getOwner());
    }

    @Test
    void queryByIdsShouldReturnEmptyForEmptyInput() {
        assertTrue(collection.queryByIds("kb_1_v1", List.of()).isEmpty());
        verify(milvusClientV2, never()).query(any(QueryReq.class));
    }

    @Test
    void sanitizeKeywordShouldStripQuotesAndFoldWhitespace() {
        assertEquals("单 引号 换行", MilvusKnowledgeCollection.sanitizeKeyword("单'引号\n换行"));
        assertEquals("", MilvusKnowledgeCollection.sanitizeKeyword(""));
        assertEquals("", MilvusKnowledgeCollection.sanitizeKeyword(null));
    }
}
