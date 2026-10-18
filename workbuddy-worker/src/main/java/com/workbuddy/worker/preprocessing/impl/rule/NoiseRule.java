package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.PreprocessAction;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.preprocessing.rule.CleanRule;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;
import org.springframework.stereotype.Component;

/**
 * ⑦ 噪声处置：判定依赖组装环节噪声页标记（NOISE_PAGE），本规则按处置方式处理
 * KEEP（只统计不处置：状态回 NORMAL）/ MARK（标记，默认，仍参与内容流）/ EXCLUDE（剔除出检索文本内容流）。
 * 噪声计数来自组装环节的页标记，与处置动作无关；空白页无元素，仅统计与告警，无元素级处置。
 */
@Component
public class NoiseRule implements CleanRule {

    @Override
    public String name() {
        return "noise-dispose-v1";
    }

    @Override
    public String stepName() {
        return "噪声处置";
    }

    @Override
    public int order() {
        return 7;
    }

    @Override
    public boolean enabledIn(PreprocessStrategy strategy) {
        return NullUtil.isNotNull(strategy.rule(PreprocessRule.NOISE));
    }

    @Override
    public RuleOutcome apply(ViewElement element, RuleContext context) {
        if (NullUtil.isNull(element.getPage()) || !context.getNoisePageNumbers().contains(element.getPage())) {
            return RuleOutcome.none();
        }
        String opt = context.getStrategy().action(PreprocessRule.NOISE, PreprocessAction.MARK.name());
        return MarkDisposeSupport.dispose(element, opt,
                ViewElementStatus.EXCLUDED_NOISE.name(), ViewElementStatus.NOISE.name(), name(),
                new MarkDisposeSupport.TraceTexts(
                        "策略 noise=EXCLUDE，噪声页元素剔除出检索文本",
                        "策略 noise=KEEP，保留原样（只统计不处置）",
                        "组装环节噪声页标记，处置=标记（仍参与内容流）"));
    }
}
