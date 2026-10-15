package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.KbRetrievalRun;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.Collection;
import java.util.List;

/**
 * 检索运行记录数据访问服务。
 *
 * @author cxxl
 */
public interface KbRetrievalRunDbService extends InfraDbService<KbRetrievalRun> {

    /** 知识库运行记录（新→旧，limit 截断；0/空 = 全部） */
    List<KbRetrievalRun> listByKb(Long knowledgeBaseId, int limit);

    /**
     * 按 ID 列表取记录并保持传入顺序（勾选对比按勾选顺序并排；缺失 ID 跳过）。
     */
    List<KbRetrievalRun> listByIdsOrdered(Collection<Long> ids);
}
