package com.workbuddy.infra.vector;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.utils.NullUtil;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库向量集合实现（Milvus 原生 SDK 口径）：
 * 集合生命周期编排 + 行读写；schema/检索/行编解码拆到同包支撑类。
 *
 * <p>口径钉版：全文通道字段（content/title_path，BM25 + chinese analyzer）、标量倒排字段
 * （document_id/owner/content_type）、向量通道（FloatVector + HNSW + COSINE）、显式 STRONG 一致性。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "knowledge.vector.milvus.enable", havingValue = "true", matchIfMissing = true)
public class MilvusKnowledgeCollection implements KnowledgeCollectionPort {

    /** 全文通道字段（BM25 函数 + 稀疏向量自动附加；chinese analyzer） */
    public static final List<String> ANALYZER_FIELDS = MilvusCollectionSchema.ANALYZER_FIELDS;

    /** 标量倒排索引字段（大库下过滤性能下限保障） */
    public static final List<String> INVERTED_FIELDS = MilvusCollectionSchema.INVERTED_FIELDS;

    /** 必备字段（schema 回读校验用） */
    public static final List<String> REQUIRED_FIELDS = MilvusCollectionSchema.REQUIRED_FIELDS;

    /** content 字段最大长度 */
    public static final int CONTENT_MAX_LENGTH = MilvusCollectionSchema.CONTENT_MAX_LENGTH;

    /** source_element_ids 最大长度 */
    public static final int SOURCE_ELEMENT_IDS_MAX_LENGTH =
            MilvusCollectionSchema.SOURCE_ELEMENT_IDS_MAX_LENGTH;

    /** listIds 分页大小 */
    private static final long PAGE_SIZE = 1000;

    private final MilvusClientV2 milvusClientV2;
    private final MilvusCollectionSchema schema;
    private final MilvusSearchSupport searchSupport;

    /**
     * 构造集合实现。
     *
     * @param milvusClientV2 Milvus 客户端
     */
    public MilvusKnowledgeCollection(MilvusClientV2 milvusClientV2) {
        this.milvusClientV2 = milvusClientV2;
        this.schema = new MilvusCollectionSchema(milvusClientV2);
        this.searchSupport = new MilvusSearchSupport(milvusClientV2);
    }

    /**
     * 确保集合存在：不存在时按维度创建，随后校验 schema 与维度一致。
     *
     * @param collectionName 集合名
     * @param dimension      向量维度
     */
    @Override
    public void ensureCollection(String collectionName, int dimension) {
        schema.ensure(collectionName, dimension);
    }

    /**
     * 把集合加载进内存做预热：未加载的集合不接受检索。
     *
     * @param collectionName 集合名
     */
    @Override
    public void load(String collectionName) {
        milvusClientV2.loadCollection(LoadCollectionReq.builder().collectionName(collectionName).build());
        log.info("===> MilvusKnowledgeCollection 集合已加载（预热）, collection={}", collectionName);
    }

    /**
     * 回收集合，即时释放存储与内存。
     *
     * @param collectionName 集合名
     */
    @Override
    public void drop(String collectionName) {
        milvusClientV2.dropCollection(DropCollectionReq.builder().collectionName(collectionName).build());
        log.info("===> MilvusKnowledgeCollection 集合已回收（drop 即时释放）, collection={}", collectionName);
    }

    /**
     * 按主键 upsert 行集；空行集直接跳过。
     *
     * @param collectionName 集合名
     * @param rows           待写入的行
     */
    @Override
    public void upsert(String collectionName, List<CollectionRow> rows) {
        if (rows.isEmpty()) {
            log.info("===> MilvusKnowledgeCollection 追加跳过：空行集, collection={}", collectionName);
            return;
        }
        milvusClientV2.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(rows.stream().map(MilvusRowCodec::toJson).toList())
                .build());
        log.info("===> MilvusKnowledgeCollection 追加写入, collection={}, rows={}", collectionName, rows.size());
    }

    /**
     * 向量检索：按相似度取前 topK 行，并带上每行的分数。
     *
     * @param collectionName 集合名
     * @param vector         查询向量
     * @param topK           返回条数上限
     * @param filter         过滤表达式（可空）
     * @return 命中的行与相似度分数，按分数降序
     */
    @Override
    public List<ScoredRow> searchVector(String collectionName, List<Float> vector, int topK, String filter) {
        return searchSupport.searchVector(collectionName, vector, topK, filter);
    }

    /**
     * 全文检索：关键词经清洗后走稀疏向量字段，返回命中的行（不带分数）。
     *
     * @param collectionName 集合名
     * @param keyword        检索关键词
     * @param limit          返回条数上限
     * @param filter         过滤表达式（可空）
     * @return 命中的行，按相关度降序
     */
    @Override
    public List<CollectionRow> searchFullText(String collectionName, String keyword, long limit, String filter) {
        return searchSupport.searchFullText(collectionName, sanitizeKeyword(keyword), limit, filter);
    }

    /**
     * 按过滤条件分页列出集合内的主键。
     *
     * @param collectionName 集合名
     * @param filter         过滤表达式（可空）
     * @return 主键列表
     */
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

    /**
     * 按主键批量取行。
     *
     * @param collectionName 集合名
     * @param ids            主键集合
     * @return 命中的行；主键为空时返回空列表
     */
    @Override
    public List<CollectionRow> queryByIds(String collectionName, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        QueryResp resp = milvusClientV2.query(QueryReq.builder()
                .collectionName(collectionName)
                .ids(new ArrayList<>(ids))
                .outputFields(MilvusSearchSupport.OUTPUT_FIELDS)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        List<CollectionRow> rows = new ArrayList<>(resp.getQueryResults().size());
        for (QueryResp.QueryResult result : resp.getQueryResults()) {
            rows.add(MilvusRowCodec.toRow(result.getEntity()));
        }
        return rows;
    }

    /**
     * 过滤表达式字符串清洗：Milvus 过滤表达式字符串字面量不允许原始换行/制表符，单引号会破坏语法——
     * 换行/制表转空格、单引号剔除、连续空白折叠（全文关键词与过滤值共用口径）。
     *
     * @param keyword 原始关键词或过滤值
     * @return 清洗后的字符串；入参空白时返回空串
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
}
