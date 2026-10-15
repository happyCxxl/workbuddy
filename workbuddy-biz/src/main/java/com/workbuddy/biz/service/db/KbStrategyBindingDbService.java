package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.KbStrategyBinding;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.Collection;
import java.util.List;

/**
 * 知识库-策略绑定数据访问服务。
 *
 * @author cxxl
 */
public interface KbStrategyBindingDbService extends InfraDbService<KbStrategyBinding> {

    /**
     * 取知识库某类型的有效绑定（无 → null）。
     */
    KbStrategyBinding getByKbAndType(Long knowledgeBaseId, String strategyType);

    /**
     * 取知识库某类型的任意绑定（含已逻辑删除；用于解绑后重新绑定复用原行，无 → null）。
     */
    KbStrategyBinding getAnyByKbAndType(Long knowledgeBaseId, String strategyType);

    /**
     * 取类型下多个知识库的有效绑定（列表页批量填充绑定摘要用）。
     */
    List<KbStrategyBinding> listActiveByTypeAndKbIds(String strategyType, Collection<Long> knowledgeBaseIds);

    /**
     * 是否存在指向该策略版本行的有效绑定（策略行不可变：有绑定引用禁物理删除）。
     */
    boolean existsByStrategyVersionId(Long strategyVersionId);
}
