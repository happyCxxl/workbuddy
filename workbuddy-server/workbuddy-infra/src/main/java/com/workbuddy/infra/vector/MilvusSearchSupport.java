package com.workbuddy.infra.vector;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 检索支撑：向量与 BM25 全文检索的请求组装、结果行转换。
 */
@RequiredArgsConstructor
final class MilvusSearchSupport {

    /** 命中返回字段（直取不回查库） */
    static final List<String> OUTPUT_FIELDS = List.of("id", "document_id", "owner",
            "content_type", "parent_chunk_id", "content", "title_path", "source_element_ids");

    private final MilvusClientV2 milvusClientV2;

    /**
     * 向量检索：按相似度取前 topK 行，并带每行分数。
     *
     * @param collectionName 集合名
     * @param vector         查询向量
     * @param topK           返回条数上限
     * @param filter         过滤表达式（可空）
     * @return 命中的行与相似度分数
     */
    List<ScoredRow> searchVector(String collectionName, List<Float> vector, int topK, String filter) {
        SearchResp resp = searchInternal(collectionName, new FloatVec(vector), "vector", topK, filter);
        List<SearchResp.SearchResult> results = resp.getSearchResults().isEmpty()
                ? List.of() : resp.getSearchResults().getFirst();
        List<ScoredRow> hits = new ArrayList<>(results.size());
        for (SearchResp.SearchResult result : results) {
            ScoredRow row = MilvusRowCodec.toScoredRow(result.getEntity());
            row.setScore(result.getScore());
            hits.add(row);
        }
        return hits;
    }

    /**
     * 全文检索：已清洗关键词走稀疏向量字段，返回命中行。
     *
     * @param collectionName 集合名
     * @param keyword        已清洗关键词
     * @param limit          返回条数上限
     * @param filter         过滤表达式（可空）
     * @return 命中的行
     */
    List<CollectionRow> searchFullText(String collectionName, String keyword, long limit, String filter) {
        SearchResp resp = searchInternal(collectionName, new EmbeddedText(keyword), "content_sparse",
                (int) limit, filter);
        List<SearchResp.SearchResult> results = resp.getSearchResults().isEmpty()
                ? List.of() : resp.getSearchResults().getFirst();
        List<CollectionRow> hits = new ArrayList<>(results.size());
        for (SearchResp.SearchResult result : results) {
            hits.add(MilvusRowCodec.toRow(result.getEntity()));
        }
        return hits;
    }

    /** 检索请求公共组装：向量与全文仅 data 与目标字段不同。 */
    private SearchResp searchInternal(String collectionName, BaseVector data, String annsField, int topK,
                                      String filter) {
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
}
