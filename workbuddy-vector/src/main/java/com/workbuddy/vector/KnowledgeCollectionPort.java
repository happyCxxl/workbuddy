package com.workbuddy.vector;

import java.util.List;

/**
 * 知识库向量集合 Port：按集合名寻址的通用集合管理/查询封装（口径钉版）。
 * 一个索引组合 = 一个集合（kb_{kbId}_{versionNo}）；集合即边界，查询只带业务过滤。
 *
 * @author cxxl
 */
public interface KnowledgeCollectionPort {

    /** 确保集合存在（幂等建 schema/索引/BM25 函数 + 回读校验，不一致报错 40447） */
    void ensureCollection(String collectionName, int dimension);

    /** 加载集合进内存（发布预热：READY 前置，切换零冷启动） */
    void load(String collectionName);

    /** 弃用组合/构建失败时物理删除（drop 即时释放） */
    void drop(String collectionName);

    /** 追加写入（upsert 幂等：同 id 覆盖；空行集跳过） */
    void upsert(String collectionName, List<CollectionRow> rows);

    /** 向量检索（HNSW+COSINE；filter 为业务过滤表达式） */
    List<ScoredRow> searchVector(String collectionName, List<Float> vector, int topK, String filter);

    /** 全文检索（BM25 content 通道；filter 为业务过滤表达式） */
    List<CollectionRow> searchFullText(String collectionName, String keyword, long limit, String filter);

    /** 列出集合全部 id（全量对账数据源，分页） */
    List<String> listIds(String collectionName, String filter);

    /** 按主键批量取行（父片展开数据源；空入参 → 空列表；返回顺序不保证） */
    List<CollectionRow> queryByIds(String collectionName, List<String> ids);
}
