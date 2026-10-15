package com.workbuddy.biz.service;

import com.workbuddy.common.dto.request.retrieval.RetrievalRulePublishDto;
import com.workbuddy.common.dto.response.retrieval.RetrievalRulePublishVO;

/**
 * 检索规则服务：规则列表复用策略版本机制（RETRIEVAL 白名单）
 * 与选优发布（更新索引版本行 default_rule_id + 审计）。
 *
 * @author cxxl
 */
public interface RetrievalRuleService {

    /**
     * 选优发布：校验规则存在且 RETRIEVAL 类型、版本行存在且属本 KB → 更新版本行 default_rule_id
     * + 审计 RETRIEVAL_RULE_PUBLISH（索引发布与规则发布是两个独立动作）。
     */
    RetrievalRulePublishVO publishDefaultRule(Long knowledgeBaseId, RetrievalRulePublishDto dto);
}
