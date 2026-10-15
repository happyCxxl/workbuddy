package com.workbuddy.vector;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库向量集合实现（Milvus 原生 SDK 口径）：
 * 集合生命周期（schema 建表/回读校验/加载/回收）+ 行读写（upsert/向量检索/BM25 全文检索/分页拉取/主键查询）。
 * 口径钉版：全文通道字段（content/title_path，BM25 + chinese analyzer）、标量倒排字段（document_id/owner/content_type）、
 * 向量通道（FloatVector + HNSW + COSINE）、显式 STRONG 一致性（写后立读可见）。
 *
 * @author cxxl
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "knowledge.vector.milvus.enable", havingValue = "true", matchIfMissing = true)
public class MilvusKnowledgeCollection implements KnowledgeCollectionPort {

    /**
     * 全文通道字段（BM25 函数 + 稀疏向量自动附加；chinese analyzer）
     */
    public static final List<String> ANALYZER_FIELDS = List.of("content", "title_path");

    /**
     * 标量倒排索引字段（大库下过滤性能下限保障）
     */
    public static final List<String> INVERTED_FIELDS = List.of("document_id", "owner", "content_type");

    /**
     * 必备字段（schema 回读校验用）
     */
    public static final List<String> REQUIRED_FIELDS = List.of("id", "document_id", "owner",
            "content_type", "parent_chunk_id", "content", "title_path", "source_element_ids", "vector");

    /**
     * content 字段最大长度
     */
    public static final int CONTENT_MAX_LENGTH = 8192;

    /**
     * source_element_ids 最大长度。
     *
     * <p>父片溯源是该章节全部子片元素 ID 的并集，随章节大小无界增长。
     * 实测一份 116 页招标文件（512 元素）的单父片溯源为 3503 字符，原 2048 不够用，
     * 这里放到 16384（约 900 个元素 ID）留足余量。
     */
    public static final int SOURCE_ELEMENT_IDS_MAX_LENGTH = 16384;

    /**
     * 返回字段（命中直取不回查库）
     */
    private static final List<String> OUTPUT_FIELDS = List.of("id", "document_id", "owner",
            "content_type", "parent_chunk_id", "content", "title_path", "source_element_ids");

    /**
     * listIds 分页大小
     */
    private static final long PAGE_SIZE = 1000;

    private final MilvusClientV2 milvusClientV2;

    @Override
    public void ensureCollection(String collectionName, int dimension) {
        boolean exists = Boolean.TRUE.equals(milvusClientV2.hasCollection(
                HasCollectionReq.builder().collectionName(collectionName).build()));
        if (!exists) {
            createCollection(collectionName, dimension);
            log.info("===> MilvusKnowledgeCollection 集合已创建, collection={}, dimension={}",
                    collectionName, dimension);
        }
        verifySchema(collectionName, dimension);
    }

    @Override
    public void load(String collectionName) {
        milvusClientV2.loadCollection(LoadCollectionReq.builder().collectionName(collectionName).build());
        log.info("===> MilvusKnowledgeCollection 集合已加载（预热）, collection={}", collectionName);
    }

    @Override
    public void drop(String collectionName) {
        milvusClientV2.dropCollection(DropCollectionReq.builder().collectionName(collectionName).build());
        log.info("===> MilvusKnowledgeCollection 集合已回收（drop 即时释放）, collection={}", collectionName);
    }

    @Override
    public void upsert(String collectionName, List<CollectionRow> rows) {
        if (rows.isEmpty()) {
            log.info("===> MilvusKnowledgeCollection 追加跳过：空行集, collection={}", collectionName);
            return;
        }
        List<JsonObject> data = rows.stream().map(this::toJson).toList();
        milvusClientV2.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(data)
                .build());
        log.info("===> MilvusKnowledgeCollection 追加写入, collection={}, rows={}", collectionName, rows.size());
    }

    @Override
    public List<ScoredRow> searchVector(String collectionName, List<Float> vector, int topK, String filter) {
        SearchResp resp = searchInternal(collectionName, new FloatVec(vector), "vector", topK, filter);
        List<SearchResp.SearchResult> results = resp.getSearchResults().isEmpty()
                ? List.of() : resp.getSearchResults().getFirst();
        List<ScoredRow> hits = new ArrayList<>(results.size());
        for (SearchResp.SearchResult result : results) {
            ScoredRow row = toScoredRow(result.getEntity());
            row.setScore(result.getScore());
            hits.add(row);
        }
        return hits;
    }

    @Override
    public List<CollectionRow> searchFullText(String collectionName, String keyword, long limit, String filter) {
        String sanitized = sanitizeKeyword(keyword);
        SearchResp resp = searchInternal(collectionName, new EmbeddedText(sanitized), "content_sparse",
                (int) limit, filter);
        List<SearchResp.SearchResult> results = resp.getSearchResults().isEmpty()
                ? List.of() : resp.getSearchResults().getFirst();
        List<CollectionRow> hits = new ArrayList<>(results.size());
        for (SearchResp.SearchResult result : results) {
            hits.add(toRow(result.getEntity()));
        }
        return hits;
    }

    /**
     * 检索请求公共组装：向量与全文检索共用字段，仅 data 与目标字段不同
     */
    private SearchResp searchInternal(String collectionName, BaseVector data, String annsField, int topK, String filter) {
        return milvusClientV2.search(SearchReq.builder()
                .collectionName(collectionName)
                .data(List.of(data))
                .annsField(annsField)
                .topK(topK)
                .filter(filter)
                .outputFields(OUTPUT_FIELDS)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
    }

    @Override
    public List<String> listIds(String collectionName, String filter) {
        List<String> ids = new ArrayList<>();
        long offset = 0;
        while (true) {
            QueryResp resp = milvusClientV2.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .outputFields(List.of("id"))
                    .offset(offset)
                    .limit(PAGE_SIZE)
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
            List<QueryResp.QueryResult> results = resp.getQueryResults();
            if (results.isEmpty()) {
                break;
            }
            for (QueryResp.QueryResult result : results) {
                Object id = result.getEntity().get("id");
                if (NullUtil.isNotNull(id)) {
                    ids.add(String.valueOf(id));
                }
            }
            if (results.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        return ids;
    }

    @Override
    public List<CollectionRow> queryByIds(String collectionName, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        QueryResp resp = milvusClientV2.query(QueryReq.builder()
                .collectionName(collectionName)
                .ids(new ArrayList<>(ids))
                .outputFields(OUTPUT_FIELDS)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        List<CollectionRow> rows = new ArrayList<>(resp.getQueryResults().size());
        for (QueryResp.QueryResult result : resp.getQueryResults()) {
            rows.add(toRow(result.getEntity()));
        }
        return rows;
    }

    /** 建集合：schema（含 BM25 函数与稀疏向量字段）+ 向量/稀疏/标量索引 */
    private void createCollection(String collectionName, int dimension) {
        CreateCollectionReq.CollectionSchema schema = milvusClientV2.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.VarChar).maxLength(128)
                .isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("document_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("owner").dataType(DataType.VarChar).maxLength(64).isNullable(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content_type").dataType(DataType.VarChar).maxLength(32).isNullable(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("parent_chunk_id").dataType(DataType.VarChar).maxLength(128).isNullable(true).build());
        for (String field : ANALYZER_FIELDS) {
            // **不可为空**：这两个字段是下方 BM25 函数的输入字段，Milvus 明确要求函数输入
            // 字段 non-nullable —— 声明成 nullable 会让 createCollection 直接失败
            // （实测：function input field cannot be nullable: function content_bm25_emb, field content）。
            // 与写入侧口径一致：toJson() 本就把 null 归一成空串，业务上无需可空。
            schema.addField(AddFieldReq.builder()
                    .fieldName(field).dataType(DataType.VarChar)
                    .maxLength("content".equals(field) ? CONTENT_MAX_LENGTH : 1024)
                    .enableAnalyzer(true).enableMatch(true)
                    .analyzerParams(Map.of("type", "chinese")).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(field + "_sparse").dataType(DataType.SparseFloatVector).build());
        }
        schema.addField(AddFieldReq.builder()
                .fieldName("source_element_ids").dataType(DataType.VarChar)
                .maxLength(SOURCE_ELEMENT_IDS_MAX_LENGTH).isNullable(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("vector").dataType(DataType.FloatVector).dimension(dimension).build());
        for (String field : ANALYZER_FIELDS) {
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .name(field + "_bm25_emb").functionType(FunctionType.BM25)
                    .inputFieldNames(List.of(field)).outputFieldNames(List.of(field + "_sparse")).build());
        }
        milvusClientV2.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName).collectionSchema(schema)
                .consistencyLevel(ConsistencyLevel.STRONG).build());

        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder().fieldName("vector")
                .indexType(IndexParam.IndexType.HNSW).metricType(IndexParam.MetricType.COSINE).build());
        for (String field : ANALYZER_FIELDS) {
            indexes.add(IndexParam.builder().fieldName(field + "_sparse")
                    .indexType(IndexParam.IndexType.AUTOINDEX).metricType(IndexParam.MetricType.BM25).build());
        }
        for (String field : INVERTED_FIELDS) {
            indexes.add(IndexParam.builder().fieldName(field)
                    .indexType(IndexParam.IndexType.INVERTED).build());
        }
        milvusClientV2.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName).indexParams(indexes).build());
    }

    /** schema 回读校验：必备字段齐备 + 向量维度一致 + 全文通道启用 analyzer；不一致抛 40447 */
    private void verifySchema(String collectionName, int dimension) {
        DescribeCollectionResp resp = milvusClientV2.describeCollection(
                DescribeCollectionReq.builder().collectionName(collectionName).build());
        ThrowUtil.throwIf(NullUtil.isNull(resp) || NullUtil.isNull(resp.getCollectionSchema()),
                ErrorCode.INDEX_COLLECTION_SCHEMA_MISMATCH,
                "集合 schema 回读失败, collection=" + collectionName);
        CreateCollectionReq.CollectionSchema schema = resp.getCollectionSchema();
        for (String field : REQUIRED_FIELDS) {
            ThrowUtil.throwIf(NullUtil.isNull(schema.getField(field)),
                    ErrorCode.INDEX_COLLECTION_SCHEMA_MISMATCH,
                    "集合 schema 校验失败：字段缺失 " + field + ", collection=" + collectionName);
        }
        CreateCollectionReq.FieldSchema vectorField = schema.getField("vector");
        ThrowUtil.throwIf(NullUtil.isNull(vectorField.getDimension()) || vectorField.getDimension() != dimension,
                ErrorCode.INDEX_COLLECTION_SCHEMA_MISMATCH,
                "集合 schema 校验失败：向量维度不一致（期望 " + dimension
                        + "，实际 " + vectorField.getDimension() + "）, collection=" + collectionName);
        requireAnalyzer(schema, collectionName);
        log.info("===> MilvusKnowledgeCollection schema 校验通过, collection={}, dimension={}",
                collectionName, dimension);
    }

    /** content 全文通道校验：未启用 analyzer 抛 40447 */
    private void requireAnalyzer(CreateCollectionReq.CollectionSchema schema, String collectionName) {
        CreateCollectionReq.FieldSchema contentField = schema.getField("content");
        ThrowUtil.throwIf(!Boolean.TRUE.equals(contentField.getEnableAnalyzer()),
                ErrorCode.INDEX_COLLECTION_SCHEMA_MISMATCH,
                "集合 schema 校验失败：content 未启用 analyzer, collection=" + collectionName);
    }

    /** 行 → Milvus 行 JSON（字段名与 schema 一致；null 归一化） */
    private JsonObject toJson(CollectionRow row) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", row.getId());
        obj.addProperty("document_id", row.getDocumentId());
        obj.addProperty("owner", ObjectUtil.defaultIfNull(row.getOwner(), ""));
        obj.addProperty("content_type", row.getContentType());
        addNullable(obj, "parent_chunk_id", row.getParentChunkId());
        obj.addProperty("content", row.getContent());
        obj.addProperty("title_path", ObjectUtil.defaultIfNull(row.getTitlePath(), ""));
        addNullable(obj, "source_element_ids", row.getSourceElementIds());
        JsonArray vector = new JsonArray();
        for (Float f : row.getVector()) {
            vector.add(f);
        }
        obj.add("vector", vector);
        return obj;
    }

    private void addNullable(JsonObject obj, String key, String value) {
        if (StrUtil.isBlank(value)) {
            obj.add(key, JsonNull.INSTANCE);
        } else {
            obj.addProperty(key, value);
        }
    }

    /**
     * 过滤表达式字符串清洗：Milvus 过滤表达式字符串字面量不允许原始换行/制表符，单引号会破坏语法——
     * 换行/制表转空格、单引号剔除、连续空白折叠（全文关键词与过滤值共用口径）。
     */
    public static String sanitizeKeyword(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return "";
        }
        return keyword.replace('\'', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    /** 查询实体 Map → 行（vector 字段父片展开不需要） */
    private CollectionRow toRow(Map<String, Object> entity) {
        CollectionRow row = new CollectionRow();
        fillRow(row, entity);
        return row;
    }

    private ScoredRow toScoredRow(Map<String, Object> entity) {
        ScoredRow row = new ScoredRow();
        fillRow(row, entity);
        return row;
    }

    /** 标量字段拷贝（行/命中行两类型共用） */
    private void fillRow(CollectionRow row, Map<String, Object> entity) {
        row.setId(str(entity.get("id")));
        row.setDocumentId(longOf(entity.get("document_id")));
        row.setOwner(str(entity.get("owner")));
        row.setContentType(str(entity.get("content_type")));
        row.setParentChunkId(str(entity.get("parent_chunk_id")));
        row.setContent(str(entity.get("content")));
        row.setTitlePath(str(entity.get("title_path")));
        row.setSourceElementIds(str(entity.get("source_element_ids")));
    }

    private String str(Object value) {
        return NullUtil.isNull(value) ? null : String.valueOf(value);
    }

    private Long longOf(Object value) {
        if (NullUtil.isNull(value)) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}
