package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.IndexRow;

import java.util.List;

/**
 * 索引控制面单测共享夹具：组合快照、版本行、产物行与文件行的构造口径。
 */
final class IndexSetTestFixtures {

    static final Long KB_ID = 1L;
    static final Long SET_ID = 5L;

    private IndexSetTestFixtures() {
    }

    static ComboSnapshot comboAll() {
        return ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
    }

    static ComboSnapshot comboList(List<Long> fileIds, String embedStrategy) {
        ComboSnapshot combo = ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", embedStrategy);
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(fileIds);
        return combo;
    }

    static WbFileResult file(Long id) {
        WbFileResult file = new WbFileResult();
        file.setId(id);
        file.setKnowledgeBaseId(KB_ID);
        file.setOwner("userA");
        return file;
    }

    static WbIndexSet indexSet() {
        WbIndexSet set = new WbIndexSet();
        set.setId(SET_ID);
        set.setKnowledgeBaseId(KB_ID);
        return set;
    }

    static WbKnowledgeBase kbOn() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(KB_ID);
        kb.setStrategyBindingEnabled(1);
        return kb;
    }

    static WbKnowledgeBase kbOff() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(KB_ID);
        kb.setStrategyBindingEnabled(0);
        return kb;
    }

    static WbIndexVersion versionRow(Long id, String versionNo, String status) {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(id);
        version.setIndexSetId(SET_ID);
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        version.setChunkCount(7);
        version.setVectorCount(9);
        return version;
    }

    static WbIndexVersion listVersion(String versionNo, String status, ComboSnapshot combo) {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(900L);
        version.setIndexSetId(SET_ID);
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setComboSnapshot(JsonUtil.toJsonStr(combo));
        return version;
    }

    static IndexRow indexRow() {
        IndexRow row = new IndexRow();
        row.setChunkId("chunk-1");
        row.setDocumentId(10L);
        row.setOwner("userA");
        row.setContentType("PARAGRAPH");
        row.setContent("投标保证金叁万元");
        row.setVector(List.of(1.0f, 2.0f));
        return row;
    }

    static WbChunkSet chunkRowOf(Long id, Long fileId, String chunkStrategy, Long upstreamProductId) {
        WbChunkSet row = new WbChunkSet();
        row.setId(id);
        row.setFileResultId(fileId);
        row.setChunkStrategyVersion(chunkStrategy);
        row.setUpstreamProductId(upstreamProductId);
        return row;
    }

    static WbEmbeddingSet embedRowOf(Long id, Long fileId, String strategyVersion, int recordCount) {
        WbEmbeddingSet row = new WbEmbeddingSet();
        row.setId(id);
        row.setFileResultId(fileId);
        row.setStrategyVersion(strategyVersion);
        row.setRecordCount(recordCount);
        return row;
    }
}
