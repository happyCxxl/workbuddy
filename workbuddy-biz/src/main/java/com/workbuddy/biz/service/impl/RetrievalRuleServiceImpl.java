package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.RetrievalRuleService;
import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.dto.request.retrieval.RetrievalRulePublishDTO;
import com.workbuddy.common.dto.response.retrieval.RetrievalRulePublishVO;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 检索规则服务实现：规则列表复用策略版本机制（RETRIEVAL 白名单）；
 * 选优发布 = 版本行 default_rule_id 切换 + 审计（规则行不可变，发布即切指针，评测可复现）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalRuleServiceImpl implements RetrievalRuleService {

    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.INDEX_VERSION.key();

    private final StrategyVersionService strategyVersionService;

    private final WbPipelineStrategyVersionDbService strategyVersionDbService;

    private final WbIndexVersionDbService indexVersionDbService;

    private final WbIndexSetDbService indexSetDbService;

    private final WbAuditLogDbService kbAuditLogDbService;

    /**
     * 规则列表（type=RETRIEVAL）。
     *
     * @param includeInactive 是否包含停用版本（测试台选择器传 false，管理页传 true）
     * @return 检索规则版本列表
     */
    public List<StrategyVersionVO> listRules(boolean includeInactive) {
        return strategyVersionService.list("RETRIEVAL", includeInactive);
    }

    /**
     * 发布知识库的默认检索规则：校验版本归属后写回默认规则列。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param dto             规则发布请求
     * @return 发布结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RetrievalRulePublishVO publishDefaultRule(Long knowledgeBaseId, RetrievalRulePublishDTO dto) {
        WbIndexVersion version = indexVersionDbService.getById(dto.getVersionId());
        ThrowUtil.throwIf(NullUtil.isNull(version), ErrorCode.INDEX_VERSION_NOT_FOUND);
        WbIndexSet set = indexSetDbService.getById(version.getIndexSetId());
        ThrowUtil.throwIf(NullUtil.isNull(set)
                        || !Objects.equals(set.getKnowledgeBaseId(), knowledgeBaseId),
                ErrorCode.INDEX_VERSION_NOT_FOUND, "版本行不属于该知识库");

        WbPipelineStrategyVersion rule = strategyVersionDbService.getById(dto.getRuleId());
        ThrowUtil.throwIf(NullUtil.isNull(rule) || !"RETRIEVAL".equals(rule.getType()),
                ErrorCode.RETRIEVAL_RULE_NOT_FOUND);

        Long oldRuleId = version.getDefaultRuleId();
        version.setDefaultRuleId(rule.getId());
        indexVersionDbService.updateById(version);
        kbAuditLogDbService.saveAudit(AuditActionType.RETRIEVAL_RULE_PUBLISH, AUDIT_OBJECT_TYPE,
                version.getId(), NullUtil.isNull(oldRuleId) ? null : String.valueOf(oldRuleId),
                String.valueOf(rule.getId()));
        log.info("===> RetrievalRuleServiceImpl 选优发布完成, kbId={}, versionId={}, versionNo={}, rule={}-{}, oldRuleId={}",
                knowledgeBaseId, version.getId(), version.getVersionNo(), rule.getName(), rule.getVersion(), oldRuleId);

        RetrievalRulePublishVO vo = new RetrievalRulePublishVO();
        vo.setVersionId(version.getId());
        vo.setVersionNo(version.getVersionNo());
        vo.setRuleId(rule.getId());
        vo.setRuleNameVersion(rule.getName() + "-" + rule.getVersion());
        return vo;
    }
}
