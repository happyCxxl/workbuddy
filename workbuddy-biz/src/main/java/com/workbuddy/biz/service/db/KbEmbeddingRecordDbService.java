package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.KbEmbeddingRecord;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 向量记录数据访问服务（批量插入由 saveBatch(list, size) 承担，≤500/批）。
 *
 * @author cxxl
 */
public interface KbEmbeddingRecordDbService extends InfraDbService<KbEmbeddingRecord> {

    /**
     * 按集合取全部向量记录（ID 升序；详情抽屉列表数据源）。
     */
    List<KbEmbeddingRecord> listByEmbeddingSetId(Long embeddingSetId);
}
