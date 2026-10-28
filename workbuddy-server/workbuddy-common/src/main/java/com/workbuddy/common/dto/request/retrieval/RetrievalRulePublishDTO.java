package com.workbuddy.common.dto.request.retrieval;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 检索规则选优发布请求：把规则行设为指定索引版本行的默认规则。
 */
@Data
public class RetrievalRulePublishDTO {

    /** 索引版本行 ID（在线/候选/冻结集均可，发布索引与发布规则是两个独立动作） */
    @NotNull(message = "versionId 不能为空")
    private Long versionId;

    /** 规则行 ID（wb_knowledge_pipeline_strategy_version，type=RETRIEVAL） */
    @NotNull(message = "ruleId 不能为空")
    private Long ruleId;
}
