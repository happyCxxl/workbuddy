package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.KnowledgeBaseBindingService;
import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.access.KnowledgeBaseAccessSupport;
import com.workbuddy.biz.service.support.access.KnowledgeBaseBindingSupport;
import com.workbuddy.common.annotation.Audit;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateDTO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库策略绑定用例实现：单类型绑定/解绑、批量设置策略集合与双向查询。
 *
 * <p>绑定开关关闭（评测模式）的知识库拒绝一切绑定；批量设置按「整套覆盖」校验，
 * 逐类型复用单类型绑定逻辑，外层事务覆盖，任一项失败整体回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseBindingServiceImpl implements KnowledgeBaseBindingService {

    private final WbStrategyBindingDbService strategyBindingDbService;

    private final WbPipelineStrategyVersionDbService strategyVersionDbService;

    private final KnowledgeBaseAccessSupport accessSupport;

    private final KnowledgeBaseBindingSupport bindingSupport;

    /**
     * 取知识库在某策略类型上的绑定。
     *
     * @param id           知识库 ID
     * @param strategyType 策略类型码值
     * @return 绑定视图
     */
    @Override
    public StrategyBindingVO strategyBinding(Long id, String strategyType) {
        WbKnowledgeBase kb = accessSupport.getAccessibleById(id);
        ThrowUtil.throwIf(StrUtil.isBlank(strategyType)
                        || !StrategyVersionService.BINDABLE_TYPES.contains(strategyType),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + strategyType);
        return bindingSupport.getBinding(kb.getId(), strategyType);
    }

    /**
     * 列出某策略类型下全部知识库的绑定。
     *
     * @param strategyType 策略类型码值
     * @return 绑定视图列表
     */
    @Override
    public List<StrategyBindingVO> strategyBindings(String strategyType) {
        ThrowUtil.throwIf(StrUtil.isBlank(strategyType)
                        || !StrategyVersionService.BINDABLE_TYPES.contains(strategyType),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + strategyType);
        return bindingSupport.listBindings(strategyType);
    }

    /**
     * 绑定单个策略版本到知识库（versionId 为 null 表示解绑）。
     *
     * @param id  知识库 ID
     * @param dto 绑定请求
     * @return 成功为 true
     */
    @Audit(action = AuditActionType.BIND, objectType = AuditObjectType.KNOWLEDGE_BASE, id = "#p0")
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean bindStrategy(Long id, StrategyBindingUpdateDTO dto) {
        requireBindable(id);
        doBind(id, dto.getStrategyType(), dto.getStrategyVersionId());
        return true;
    }

    /**
     * 批量设置知识库策略集合：一次给全可绑定类型且每项都要有版本，逐类型审计 BIND。
     *
     * @param id      知识库 ID
     * @param request 批量绑定请求
     * @return 成功为 true
     */
    @Audit(action = AuditActionType.BIND, objectType = AuditObjectType.KNOWLEDGE_BASE, id = "#p0")
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean bindStrategies(Long id, StrategyBindingsUpdateDTO request) {
        requireBindable(id);
        List<StrategyBindingsUpdateDTO.StrategyBindItem> bindings = request.getBindings();
        ThrowUtil.throwIf(NullUtil.isNull(bindings) || bindings.isEmpty(),
                ErrorCode.PARAM_INVALID, "绑定列表不能为空");
        // 白名单 + 请求内类型不重复
        Set<String> seen = new HashSet<>();
        for (StrategyBindingsUpdateDTO.StrategyBindItem item : bindings) {
            String type = item.getStrategyType();
            ThrowUtil.throwIf(!StrategyVersionService.BINDABLE_TYPES.contains(type),
                    ErrorCode.PARAM_INVALID, "未知策略类型: " + type);
            ThrowUtil.throwIf(!seen.add(type), ErrorCode.PARAM_INVALID, "策略类型重复: " + type);
        }
        // 本接口是「设置整套绑定」：必须一次给全三件套，且每项都要有版本
        // （允许"开关开着但还没绑"的中间态由不调用本接口来表达；一旦调用就必须给全）
        ThrowUtil.throwIf(!seen.containsAll(StrategyVersionService.BINDABLE_TYPES),
                ErrorCode.PARAM_INVALID, "需一次设置全部可绑定策略（预处理 / 切片 / 向量化）");
        for (StrategyBindingsUpdateDTO.StrategyBindItem item : bindings) {
            ThrowUtil.throwIf(NullUtil.isNull(item.getStrategyVersionId()),
                    ErrorCode.PARAM_INVALID, "策略版本不能为空: " + item.getStrategyType());
        }
        // 局部更新：逐类型复用单类型绑定逻辑（校验/upsert/审计）；外层事务覆盖，任一项失败整体回滚
        for (StrategyBindingsUpdateDTO.StrategyBindItem item : bindings) {
            requireBindable(id);
            doBind(id, item.getStrategyType(), item.getStrategyVersionId());
        }
        log.info("===> KnowledgeBaseBindingServiceImpl bindStrategies 批量绑定知识库策略集合, id={}, count={}",
                id, bindings.size());
        return true;
    }

    /**
     * 绑定前置：可访问 + 绑定开关未关闭。
     *
     * @param id 知识库 ID
     */
    private void requireBindable(Long id) {
        WbKnowledgeBase kb = accessSupport.getAccessibleById(id);
        ThrowUtil.throwIf(bindingSupport.isBindingDisabled(kb),
                ErrorCode.PARAM_INVALID, "该知识库已关闭策略绑定（评测模式），禁止绑定策略");
    }

    /**
     * 单类型绑定的公共实现：类型白名单、解绑 / 版本校验 / upsert / 日志。
     *
     * @param id        知识库 ID
     * @param type      策略类型
     * @param versionId 策略版本 ID；为 null 表示解绑
     */
    private void doBind(Long id, String type, Long versionId) {
        ThrowUtil.throwIf(!StrategyVersionService.BINDABLE_TYPES.contains(type),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + type);

        // 解绑：绑定行逻辑删除（无绑定行时幂等成功）
        if (NullUtil.isNull(versionId)) {
            WbStrategyBinding existing = strategyBindingDbService.getByKbAndType(id, type);
            if (NullUtil.isNotNull(existing)) {
                existing.setDelFlag(DelFlag.DELETED.getCode());
                strategyBindingDbService.updateById(existing);
            }
            log.info("===> KnowledgeBaseBindingServiceImpl bindStrategy 解绑知识库策略, id={}, type={}", id, type);
            return;
        }

        WbPipelineStrategyVersion version = strategyVersionDbService.getById(versionId);
        ThrowUtil.throwIf(NullUtil.isNull(version), ErrorCode.PARAM_INVALID, "策略版本不存在");
        ThrowUtil.throwIf(!type.equals(version.getType()), ErrorCode.PARAM_INVALID, "策略类型与所选版本不匹配");
        ThrowUtil.throwIf(!RowStatus.ACTIVE.name().equals(version.getStatus()),
                ErrorCode.PARAM_INVALID, "策略已停用，请先启用后再绑定");

        // 绑定/重绑：复用原行（含已逻辑删除行），uk 不被占用
        WbStrategyBinding existing = strategyBindingDbService.getAnyByKbAndType(id, type);
        if (NullUtil.isNull(existing)) {
            WbStrategyBinding binding = new WbStrategyBinding();
            binding.setKnowledgeBaseId(id);
            binding.setStrategyType(type);
            binding.setStrategyVersionId(versionId);
            strategyBindingDbService.save(binding);
        } else {
            existing.setDelFlag(DelFlag.NORMAL.getCode());
            existing.setStrategyVersionId(versionId);
            strategyBindingDbService.updateById(existing);
        }
        log.info("===> KnowledgeBaseBindingServiceImpl bindStrategy 绑定知识库策略, id={}, type={}, versionId={}",
                id, type, versionId);
    }
}
