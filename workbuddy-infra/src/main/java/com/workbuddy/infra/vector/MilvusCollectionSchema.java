package com.workbuddy.infra.vector;

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
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Milvus 知识库集合的 schema：建集合、回读校验与全文 analyzer 校验。
 */
@Slf4j
@RequiredArgsConstructor
final class MilvusCollectionSchema {

    /** 全文通道字段（BM25 函数 + 稀疏向量自动附加；chinese analyzer） */
    static final List<String> ANALYZER_FIELDS = List.of("content", "title_path");

    /** 标量倒排索引字段（大库下过滤性能下限保障） */
    static final List<String> INVERTED_FIELDS = List.of("document_id", "owner", "content_type");

    /** 必备字段（schema 回读校验用） */
    static final List<String> REQUIRED_FIELDS = List.of("id", "document_id", "owner",
            "content_type", "parent_chunk_id", "content", "title_path", "source_element_ids", "vector");

    /** content 字段最大长度 */
    static final int CONTENT_MAX_LENGTH = 8192;

    /** source_element_ids 最大长度 */
    static final int SOURCE_ELEMENT_IDS_MAX_LENGTH = 16384;

    private final MilvusClientV2 milvusClientV2;

    /**
     * 确保集合存在并校验 schema；不存在时按维度创建。
     *
     * @param collectionName 集合名
     * @param dimension      向量维度
     */
    void ensure(String collectionName, int dimension) {
        boolean exists = Boolean.TRUE.equals(milvusClientV2.hasCollection(
                HasCollectionReq.builder().collectionName(collectionName).build()));
        if (!exists) {
            create(collectionName, dimension);
            log.info("===> MilvusCollectionSchema 集合已创建, collection={}, dimension={}",
                    collectionName, dimension);
        }
        verify(collectionName, dimension);
    }

    /** 建集合：schema（含 BM25 函数与稀疏向量字段）+ 向量/稀疏/标量索引 */
    private void create(String collectionName, int dimension) {
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
            // 不可为空：BM25 函数输入字段必须 non-nullable；写入侧把 null 归一成空串。
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
    private void verify(String collectionName, int dimension) {
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
        log.info("===> MilvusCollectionSchema schema 校验通过, collection={}, dimension={}",
                collectionName, dimension);
    }

    /** content 全文通道校验：未启用 analyzer 抛 40447 */
    private void requireAnalyzer(CreateCollectionReq.CollectionSchema schema, String collectionName) {
        CreateCollectionReq.FieldSchema contentField = schema.getField("content");
        ThrowUtil.throwIf(!Boolean.TRUE.equals(contentField.getEnableAnalyzer()),
                ErrorCode.INDEX_COLLECTION_SCHEMA_MISMATCH,
                "集合 schema 校验失败：content 未启用 analyzer, collection=" + collectionName);
    }
}
