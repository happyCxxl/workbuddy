package com.workbuddy.worker.structure.judge;

import com.workbuddy.common.domain.structure.ContinuationJudgeContext;
import com.workbuddy.common.domain.structure.ContinuationJudgeResult;
import com.workbuddy.common.domain.structure.TitleJudgeContext;
import com.workbuddy.common.domain.structure.TitleJudgeResult;

/**
 * 结构判定模型能力接口（预留，暂未接入实现）：
 * "拿不准"场景（标题候选/疑似续表）在固定规则之后、告警之前调用；
 * 实现一个类 + 注册 + 打开 knowledge.structure.model-fallback.enabled 即接入，主流程零改动。
 */
public interface StructureJudgeProvider {

    /**
     * 判定候选标题。
     *
     * @param context 候选上下文
     * @return 标题判定结果（isTitle/level/confidence/model/version）
     */
    TitleJudgeResult judgeTitle(TitleJudgeContext context);

    /**
     * 判定疑似续表。
     *
     * @param context 两表上下文
     * @return 续表判定结果（isContinuation/confidence/model/version）
     */
    ContinuationJudgeResult judgeContinuation(ContinuationJudgeContext context);
}
