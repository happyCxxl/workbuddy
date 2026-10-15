package com.workbuddy.worker.indexing;

import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.FullTextQuery;
import com.workbuddy.worker.indexing.search.VectorHit;
import com.workbuddy.worker.indexing.search.VectorQuery;

import java.util.List;

/**
 * Milvus 索引 Port（策略集合模型）。
 * 一个组合 = 一个集合（kb_{kbId}_{versionNo}）；本 Port 所有操作按集合名寻址，
 * 集合即边界：无 version 过滤、无 kb_id 过滤，查询只带业务过滤（documentId/owner/contentType）。
 *
 * @author cxxl
 */
public interface MilvusIndexPort {

    /** 集合命名：kb_{kbId}_{versionNo}（versionNo = 组合注册序号） */
    static String collectionName(Long knowledgeBaseId, String versionNo) {
        return "kb_" + knowledgeBaseId + "_" + versionNo;
    }

    /** 确保集合存在（幂等建 schema/索引/BM25 函数 + 回读校验，不一致报错） */
    void ensureCollection(String collectionName, int dimension);

    /** 加载集合进内存（发布预热：READY 前置，切换零冷启动） */
    void load(String collectionName);

    /** 弃用组合/构建失败时物理删除（drop 即时释放） */
    void drop(String collectionName);

    /** 追加写入（upsert 幂等：同 chunkId 覆盖；重试/并发双触发无害） */
    void append(String collectionName, List<IndexRow> rows);

    /** 向量检索（HNSW+COSINE；仅业务过滤） */
    List<VectorHit> searchVector(String collectionName, VectorQuery query);

    /** 全文检索（BM25 content 通道；仅业务过滤） */
    List<FullTextHit> searchFullText(String collectionName, FullTextQuery query);

    /** 列出集合全部 chunkId（全量对账数据源，分页） */
    List<String> listChunkIds(String collectionName);

    /** 列出集合内指定文件的 chunkId（追加批次对账数据源，分页） */
    List<String> listChunkIds(String collectionName, Long documentId);

    /** 按 chunkId（主键）批量取行（父片展开数据源；空入参 → 空列表；返回顺序不保证） */
    List<IndexRow> queryChunkByIds(String collectionName, List<String> chunkIds);
}
