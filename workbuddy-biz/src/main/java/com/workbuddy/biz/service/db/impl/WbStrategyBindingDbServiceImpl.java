package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.WbStrategyBindingMapper;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 知识库-策略绑定数据访问服务实现。
 */
@Service
public class WbStrategyBindingDbServiceImpl
        extends InfraDbServiceImpl<WbStrategyBindingMapper, WbStrategyBinding>
        implements WbStrategyBindingDbService {

    /**
     * 取知识库在某策略类型上的生效绑定。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param strategyType    策略类型码值
     * @return 绑定行；没有则为 null
     */
    @Override
    public WbStrategyBinding getByKbAndType(Long knowledgeBaseId, String strategyType) {
        LambdaQueryWrapper<WbStrategyBinding> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbStrategyBinding::getKnowledgeBaseId, knowledgeBaseId)
                .eq(WbStrategyBinding::getStrategyType, strategyType)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 取知识库在某策略类型上的绑定（不区分启用状态）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param strategyType    策略类型码值
     * @return 绑定行；没有则为 null
     */
    @Override
    public WbStrategyBinding getAnyByKbAndType(Long knowledgeBaseId, String strategyType) {
        LambdaQueryWrapper<WbStrategyBinding> queryWrapper = new LambdaQueryWrapper<>();
        // 显式条件覆盖逻辑删除自动过滤（del_flag 在 0/1 之间取，等价于不过滤），
        // 供解绑后重新绑定复用原行（uk(knowledge_base_id, strategy_type) 不被已删行占用）
        queryWrapper.eq(WbStrategyBinding::getKnowledgeBaseId, knowledgeBaseId)
                .eq(WbStrategyBinding::getStrategyType, strategyType)
                .in(WbStrategyBinding::getDelFlag, DelFlag.NORMAL.getCode(), DelFlag.DELETED.getCode())
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 批量取多个知识库在某策略类型上的生效绑定。
     *
     * @param strategyType    策略类型码值
     * @param knowledgeBaseIds 知识库 ID 集合
     * @return 绑定列表；入参为空时返回空列表
     */
    @Override
    public List<WbStrategyBinding> listActiveByTypeAndKbIds(String strategyType, Collection<Long> knowledgeBaseIds) {
        if (NullUtil.isNull(knowledgeBaseIds) || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WbStrategyBinding> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbStrategyBinding::getStrategyType, strategyType)
                .in(WbStrategyBinding::getKnowledgeBaseId, knowledgeBaseIds);
        return list(queryWrapper);
    }

    /**
     * 是否有知识库绑定了该策略版本。
     *
     * @param strategyVersionId 策略版本行 ID
     * @return 有绑定为 true
     */
    @Override
    public boolean existsByStrategyVersionId(Long strategyVersionId) {
        LambdaQueryWrapper<WbStrategyBinding> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbStrategyBinding::getStrategyVersionId, strategyVersionId)
                .last("LIMIT 1");
        return count(queryWrapper) > 0;
    }
}
