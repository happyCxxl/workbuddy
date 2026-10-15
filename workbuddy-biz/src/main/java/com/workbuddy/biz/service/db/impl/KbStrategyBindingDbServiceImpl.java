package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.KbStrategyBindingMapper;
import com.workbuddy.biz.service.db.KbStrategyBindingDbService;
import com.workbuddy.common.domain.entity.KbStrategyBinding;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 知识库-策略绑定数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class KbStrategyBindingDbServiceImpl
        extends InfraDbServiceImpl<KbStrategyBindingMapper, KbStrategyBinding>
        implements KbStrategyBindingDbService {

    @Override
    public KbStrategyBinding getByKbAndType(Long knowledgeBaseId, String strategyType) {
        LambdaQueryWrapper<KbStrategyBinding> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbStrategyBinding::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KbStrategyBinding::getStrategyType, strategyType)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public KbStrategyBinding getAnyByKbAndType(Long knowledgeBaseId, String strategyType) {
        LambdaQueryWrapper<KbStrategyBinding> queryWrapper = new LambdaQueryWrapper<>();
        // 显式条件覆盖逻辑删除自动过滤（del_flag 在 0/1 之间取，等价于不过滤），
        // 供解绑后重新绑定复用原行（uk(knowledge_base_id, strategy_type) 不被已删行占用）
        queryWrapper.eq(KbStrategyBinding::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KbStrategyBinding::getStrategyType, strategyType)
                .in(KbStrategyBinding::getDelFlag, DelFlag.NORMAL.getCode(), DelFlag.DELETED.getCode())
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<KbStrategyBinding> listActiveByTypeAndKbIds(String strategyType, Collection<Long> knowledgeBaseIds) {
        if (NullUtil.isNull(knowledgeBaseIds) || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<KbStrategyBinding> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbStrategyBinding::getStrategyType, strategyType)
                .in(KbStrategyBinding::getKnowledgeBaseId, knowledgeBaseIds);
        return list(queryWrapper);
    }

    @Override
    public boolean existsByStrategyVersionId(Long strategyVersionId) {
        LambdaQueryWrapper<KbStrategyBinding> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbStrategyBinding::getStrategyVersionId, strategyVersionId)
                .last("LIMIT 1");
        return count(queryWrapper) > 0;
    }
}
