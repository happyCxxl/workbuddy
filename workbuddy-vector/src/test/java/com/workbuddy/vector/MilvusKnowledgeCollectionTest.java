package com.workbuddy.vector;

import com.google.gson.JsonObject;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Milvus 知识库集合实现单测。
 *
 * <p>守护三块契约：行读写（upsert 字段名与空值归一、检索命中映射、分页拉取、主键查询）、
 * 建表口径（schema 字段、BM25 函数、索引类型与度量——口径钉版见被测类说明）、
 * schema 回读校验（缺字段、维度不一致、analyzer 未启用均抛 40447）。
 */
@ExtendWith(MockitoExtension.class)
class MilvusKnowledgeCollectionTest {

    private static final int DIMENSION = 1024;

    @Mock
    private MilvusClientV2 milvusClientV2;

    private MilvusKnowledgeCollection collection;

    /**
     * 每个用例重建被测对象；Milvus 客户端为系统边界，用替身。
     */
    @BeforeEach
    void setUp() {
        collection = new MilvusKnowledgeCollection(milvusClientV2);
    }

    /**
     * upsert 把行转成 schema 字段名的 JSON：null 归一成空串，向量保持数组。
     *
     * <p>守护 {@code toJson}：字段名或空值归一改错即失败。
     */
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

    /**
     * 空行集合不发起任何写入。
     *
     * <p>守护 {@code upsert} 的空集合短路：去掉判断即失败（会向边界发空请求）。
     */
    @Test
    void upsertShouldSkipWhenEmpty() {
        collection.upsert("kb_1_v1", List.of());
        verify(milvusClientV2, never()).upsert(any());
    }

    /**
     * 向量检索走 vector 字段（FloatVec），命中行带分数。
     *
     * <p>守护 {@code searchVector} 的 annsField 与命中映射：字段名或分数写入改错即失败。
     */
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

    /**
     * 全文检索走 content_sparse 字段，关键词先清洗。
     *
     * <p>守护 {@code sanitizeKeyword} 与全文通道字段名：清洗漏掉单引号或换行即失败。
     */
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

    /**
     * 分页拉取按页大小推进，直到不足一页收口；主键空值跳过。
     *
     * <p>守护 {@code listIds} 的翻页推进与收口：把「不足一页即停」改成死循环即失败（超时）。
     */
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
        verify(milvusClientV2, times(2)).query(captor.capture());
        assertEquals(0L, captor.getAllValues().get(0).getOffset());
        assertEquals(1000L, captor.getAllValues().get(1).getOffset());
        assertEquals(1001, ids.size());
        assertEquals("c0", ids.getFirst());
        assertEquals("c1000", ids.get(1000));
    }

    /**
     * 主键查询映射行字段，缺失字段容忍为 null。
     *
     * <p>守护 {@code fillRow} 的空值容忍：把 null 透传改成抛错即失败。
     */
    @Test
    void queryByIdsShouldMapRowsAndTolerateNulls() {
        Map<String, Object> entity = new HashMap<>();
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

    /**
     * document_id 以数字字符串返回时按数值解析。
     *
     * <p>守护 {@code longOf} 的字符串分支：删掉字符串解析即失败（数字字符串变 null）。
     */
    @Test
    void queryByIdsShouldParseNumericStringDocumentId() {
        Map<String, Object> entity = new HashMap<>();
        entity.put("id", "c9");
        entity.put("document_id", "42");
        QueryResp.QueryResult result = mock(QueryResp.QueryResult.class);
        when(result.getEntity()).thenReturn(entity);
        QueryResp resp = mock(QueryResp.class);
        when(resp.getQueryResults()).thenReturn(List.of(result));
        when(milvusClientV2.query(any(QueryReq.class))).thenReturn(resp);

        List<CollectionRow> rows = collection.queryByIds("kb_1_v1", List.of("c9"));

        assertEquals(42L, rows.getFirst().getDocumentId());
    }

    /**
     * 主键为空集合时不发起查询。
     *
     * <p>守护 {@code queryByIds} 的空输入短路：去掉判断即失败（向边界发空主键请求）。
     */
    @Test
    void queryByIdsShouldReturnEmptyForEmptyInput() {
        assertTrue(collection.queryByIds("kb_1_v1", List.of()).isEmpty());
        verify(milvusClientV2, never()).query(any(QueryReq.class));
    }

    /**
     * 清洗口径：单引号剔除、换行制表转空格、连续空白折叠、空白入参回空串。
     *
     * <p>守护 {@code sanitizeKeyword}：Milvus 过滤表达式不允许这些字符，漏清洗即上线报语法错。
     */
    @Test
    void sanitizeKeywordShouldStripQuotesAndFoldWhitespace() {
        assertEquals("单 引号 换行", MilvusKnowledgeCollection.sanitizeKeyword("单'引号\n换行"));
        assertEquals("", MilvusKnowledgeCollection.sanitizeKeyword(""));
        assertEquals("", MilvusKnowledgeCollection.sanitizeKeyword(null));
        assertEquals("a b", MilvusKnowledgeCollection.sanitizeKeyword("  a\t\tb  "));
    }

    /**
     * 集合已存在且 schema 匹配时不重建，只做回读校验。
     *
     * <p>守护 {@code ensureCollection} 的存在短路：去掉判断即失败（会对既有集合重复建表）。
     */
    @Test
    void ensureCollectionShouldVerifyWithoutRecreate() {
        when(milvusClientV2.hasCollection(any())).thenReturn(true);
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(DescribeCollectionResp.builder()
                        .collectionSchema(validSchema(DIMENSION)).build());

        collection.ensureCollection("kb_1_v1", DIMENSION);

        verify(milvusClientV2, never()).createCollection(any());
        verify(milvusClientV2, never()).createIndex(any());
    }

    /**
     * 集合不存在时按钉版口径建表：字段、BM25 函数与索引类型一一可回读。
     *
     * <p>守护 {@code createCollection} 的口径钉版：字段缺失、度量或索引类型写错即失败。
     */
    @Test
    void ensureCollectionShouldCreateWithPinnedSchemaWhenAbsent() {
        when(milvusClientV2.hasCollection(any())).thenReturn(false);
        when(milvusClientV2.createSchema()).thenReturn(CreateCollectionReq.CollectionSchema.builder().build());
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(DescribeCollectionResp.builder()
                        .collectionSchema(validSchema(DIMENSION)).build());

        collection.ensureCollection("kb_1_v1", DIMENSION);

        ArgumentCaptor<CreateCollectionReq> createCaptor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(milvusClientV2).createCollection(createCaptor.capture());
        CreateCollectionReq created = createCaptor.getValue();
        assertEquals("kb_1_v1", created.getCollectionName());
        List<String> fieldNames = created.getCollectionSchema().getFieldSchemaList().stream()
                .map(CreateCollectionReq.FieldSchema::getName).toList();
        for (String field : MilvusKnowledgeCollection.REQUIRED_FIELDS) {
            assertTrue(fieldNames.contains(field), "缺少字段 " + field);
        }
        assertTrue(fieldNames.contains("content_sparse"));
        assertTrue(fieldNames.contains("title_path_sparse"));
        assertEquals(DIMENSION, created.getCollectionSchema().getField("vector").getDimension());
        assertEquals(MilvusKnowledgeCollection.CONTENT_MAX_LENGTH,
                created.getCollectionSchema().getField("content").getMaxLength());
        assertEquals(Map.of("type", "chinese"),
                created.getCollectionSchema().getField("content").getAnalyzerParams());
        assertEquals(2, created.getCollectionSchema().getFunctionList().size());

        ArgumentCaptor<CreateIndexReq> indexCaptor = ArgumentCaptor.forClass(CreateIndexReq.class);
        verify(milvusClientV2).createIndex(indexCaptor.capture());
        Map<String, IndexParam> indexes = new HashMap<>();
        for (IndexParam param : indexCaptor.getValue().getIndexParams()) {
            indexes.put(param.getFieldName(), param);
        }
        assertEquals(IndexParam.IndexType.HNSW, indexes.get("vector").getIndexType());
        assertEquals(IndexParam.MetricType.COSINE, indexes.get("vector").getMetricType());
        assertEquals(IndexParam.MetricType.BM25, indexes.get("content_sparse").getMetricType());
        assertEquals(IndexParam.IndexType.INVERTED, indexes.get("document_id").getIndexType());
    }

    /**
     * 回读校验发现向量维度与声明不一致时抛 40447（重建集合而非静默混用）。
     *
     * <p>守护 {@code verifySchema} 的维度比对（错误码 40447）。
     */
    @Test
    void ensureCollectionShouldRejectDimensionMismatch() {
        when(milvusClientV2.hasCollection(any())).thenReturn(true);
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(DescribeCollectionResp.builder()
                        .collectionSchema(validSchema(512)).build());

        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> collection.ensureCollection("kb_1_v1", DIMENSION));

        assertEquals(ErrorCode.INDEX_COLLECTION_SCHEMA_MISMATCH.getCode(), ex.getCode());
    }

    /**
     * 回读校验发现必备字段缺失时抛 40447。
     *
     * <p>守护 {@code verifySchema} 的必备字段清点（错误码 40447）。
     */
    @Test
    void ensureCollectionShouldRejectMissingField() {
        CreateCollectionReq.CollectionSchema schema = validSchema(DIMENSION);
        schema.getFieldSchemaList().removeIf(f -> "owner".equals(f.getName()));
        when(milvusClientV2.hasCollection(any())).thenReturn(true);
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(DescribeCollectionResp.builder().collectionSchema(schema).build());

        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> collection.ensureCollection("kb_1_v1", DIMENSION));

        assertEquals(ErrorCode.INDEX_COLLECTION_SCHEMA_MISMATCH.getCode(), ex.getCode());
    }

    /**
     * 回读校验发现 content 未启用 analyzer 时抛 40447（全文通道失效）。
     *
     * <p>守护 {@code requireAnalyzer}（错误码 40447）：全文检索依赖 analyzer。
     */
    @Test
    void ensureCollectionShouldRejectAnalyzerMissing() {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        for (String field : MilvusKnowledgeCollection.REQUIRED_FIELDS) {
            if ("vector".equals(field)) {
                schema.addField(AddFieldReq.builder().fieldName("vector")
                        .dataType(DataType.FloatVector).dimension(DIMENSION).build());
            } else {
                schema.addField(AddFieldReq.builder().fieldName(field)
                        .dataType(DataType.VarChar).build());
            }
        }
        when(milvusClientV2.hasCollection(any())).thenReturn(true);
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(DescribeCollectionResp.builder().collectionSchema(schema).build());

        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> collection.ensureCollection("kb_1_v1", DIMENSION));

        assertEquals(ErrorCode.INDEX_COLLECTION_SCHEMA_MISMATCH.getCode(), ex.getCode());
    }

    /**
     * 加载与回收按集合名委托给客户端。
     *
     * <p>守护 {@code load}/{@code drop} 的边界调用：集合名传错即失败。
     */
    @Test
    void loadAndDropShouldDelegateByCollectionName() {
        collection.load("kb_1_v1");
        collection.drop("kb_1_v1");

        ArgumentCaptor<LoadCollectionReq> loadCaptor = ArgumentCaptor.forClass(LoadCollectionReq.class);
        verify(milvusClientV2).loadCollection(loadCaptor.capture());
        assertEquals("kb_1_v1", loadCaptor.getValue().getCollectionName());
        ArgumentCaptor<DropCollectionReq> dropCaptor = ArgumentCaptor.forClass(DropCollectionReq.class);
        verify(milvusClientV2).dropCollection(dropCaptor.capture());
        assertEquals("kb_1_v1", dropCaptor.getValue().getCollectionName());
    }

    /**
     * 构造一份通过校验的 schema（必备字段 + 维度一致 + content 启用 analyzer）。
     *
     * @param dimension 向量维度
     * @return schema
     */
    private CreateCollectionReq.CollectionSchema validSchema(int dimension) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        for (String field : MilvusKnowledgeCollection.REQUIRED_FIELDS) {
            switch (field) {
                case "vector" -> schema.addField(AddFieldReq.builder().fieldName("vector")
                        .dataType(DataType.FloatVector).dimension(dimension).build());
                case "content" -> schema.addField(AddFieldReq.builder().fieldName("content")
                        .dataType(DataType.VarChar).enableAnalyzer(true).build());
                default -> schema.addField(AddFieldReq.builder().fieldName(field)
                        .dataType(DataType.VarChar).build());
            }
        }
        return schema;
    }

    /**
     * 构造一行可写入的数据。
     *
     * @return 行
     */
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
}
