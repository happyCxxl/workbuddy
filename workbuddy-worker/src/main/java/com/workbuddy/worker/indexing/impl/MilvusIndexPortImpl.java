package com.workbuddy.worker.indexing.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.vector.CollectionRow;
import com.workbuddy.vector.KnowledgeCollectionPort;
import com.workbuddy.vector.MilvusKnowledgeCollection;
import com.workbuddy.vector.ScoredRow;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.FullTextQuery;
import com.workbuddy.worker.indexing.search.VectorHit;
import com.workbuddy.worker.indexing.search.VectorQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 索引 Port 实现（薄壳适配器）：业务行/命中类型 ↔ 向量库模块行类型的映射，
 * Milvus 客户端/集合/查询/口径封装全部下沉 knowledge-vector 模块。
 * 一个组合 = 一个集合（kb_{kbId}_{versionNo}）；集合即边界，查询只带业务过滤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusIndexPortImpl implements MilvusIndexPort {

    private final KnowledgeCollectionPort collectionPort;

    @Override
    public void ensureCollection(String collectionName, int dimension) {
        collectionPort.ensureCollection(collectionName, dimension);
    }

    @Override
    public void load(String collectionName) {
        collectionPort.load(collectionName);
    }

    @Override
    public void drop(String collectionName) {
        collectionPort.drop(collectionName);
    }

    @Override
    public void append(String collectionName, List<IndexRow> rows) {
        collectionPort.upsert(collectionName, rows.stream().map(this::toRow).toList());
    }

    @Override
    public List<VectorHit> searchVector(String collectionName, VectorQuery query) {
        String filter = buildFilter(query.getDocumentId(), query.getOwner(), query.getContentType());
        List<ScoredRow> rows = collectionPort.searchVector(collectionName, query.getVector(), query.getTopK(), filter);
        List<VectorHit> hits = new ArrayList<>(rows.size());
        for (ScoredRow row : rows) {
            VectorHit hit = new VectorHit();
            hit.fillFrom(row);
            hit.setScore(row.getScore());
            hits.add(hit);
        }
        return hits;
    }

    @Override
    public List<FullTextHit> searchFullText(String collectionName, FullTextQuery query) {
        String filter = buildFilter(query.getDocumentId(), query.getOwner(), query.getContentType());
        List<CollectionRow> rows = collectionPort.searchFullText(collectionName, query.getKeyword(),
                query.getLimit(), filter);
        List<FullTextHit> hits = new ArrayList<>(rows.size());
        for (CollectionRow row : rows) {
            FullTextHit hit = new FullTextHit();
            hit.fillFrom(row);
            hits.add(hit);
        }
        return hits;
    }

    @Override
    public List<String> listChunkIds(String collectionName) {
        return collectionPort.listIds(collectionName, "id != ''");
    }

    @Override
    public List<String> listChunkIds(String collectionName, Long documentId) {
        return collectionPort.listIds(collectionName, "document_id == " + documentId);
    }

    @Override
    public List<IndexRow> queryChunkByIds(String collectionName, List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        return collectionPort.queryByIds(collectionName, chunkIds).stream()
                .map(this::toIndexRow)
                .toList();
    }

    /** 索引行 → 向量库行 */
    private CollectionRow toRow(IndexRow row) {
        CollectionRow target = new CollectionRow();
        fillCommon(target, row.getChunkId(), row.getDocumentId(), row.getOwner(), row.getContentType(),
                row.getParentChunkId(), row.getContent(), row.getTitlePath(), row.getSourceElementIds());
        target.setVector(row.getVector());
        return target;
    }

    /** 向量库行 → 索引行（vector 字段父片展开不需要） */
    private IndexRow toIndexRow(CollectionRow row) {
        IndexRow target = new IndexRow();
        target.setChunkId(row.getId());
        target.setDocumentId(row.getDocumentId());
        target.setOwner(row.getOwner());
        target.setContentType(row.getContentType());
        target.setParentChunkId(row.getParentChunkId());
        target.setContent(row.getContent());
        target.setTitlePath(row.getTitlePath());
        target.setSourceElementIds(row.getSourceElementIds());
        return target;
    }

    /** 标量字段拷贝（两方向映射共用） */
    private void fillCommon(CollectionRow target, String id, Long documentId, String owner, String contentType,
                            String parentChunkId, String content, String titlePath, String sourceElementIds) {
        target.setId(id);
        target.setDocumentId(documentId);
        target.setOwner(owner);
        target.setContentType(contentType);
        target.setParentChunkId(parentChunkId);
        target.setContent(content);
        target.setTitlePath(titlePath);
        target.setSourceElementIds(sourceElementIds);
    }

    /** 业务过滤表达式（document_id 数值、owner/content_type 字符串；空段跳过） */
    private String buildFilter(Long documentId, String owner, String contentType) {
        List<String> parts = new ArrayList<>();
        if (NullUtil.isNotNull(documentId)) {
            parts.add("document_id == " + documentId);
        }
        if (StrUtil.isNotBlank(owner)) {
            parts.add("owner == '" + MilvusKnowledgeCollection.sanitizeKeyword(owner) + "'");
        }
        if (StrUtil.isNotBlank(contentType)) {
            parts.add("content_type == '" + MilvusKnowledgeCollection.sanitizeKeyword(contentType) + "'");
        }
        return String.join(" && ", parts);
    }
}
