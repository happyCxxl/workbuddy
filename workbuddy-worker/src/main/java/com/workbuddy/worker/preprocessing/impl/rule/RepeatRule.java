package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.structure.ElementMark;
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
 * ⑤ 重复处置：判定依赖组装环节标记（REPEATED_SEGMENT 元素 / REPEATED_PAGE 页，除首份外），
 * 本规则按处置方式处理 EXCLUDE（默认，只保留首份，重复份不进检索文本内容流）/ MARK（标记，重复份仍在内容流）/ KEEP（不改动，只统计）。
 * 重复份计数来自组装环节的标记，与处置动作无关。
 */
@Component
public class RepeatRule implements CleanRule {

    @Override
    public String name() {
        return "repeat-dispose-v1";
    }

    @Override
    public String stepName() {
        return "重复处置";
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public boolean enabledIn(PreprocessStrategy strategy) {
        return NullUtil.isNotNull(strategy.rule(PreprocessRule.REPEAT));
    }

    @Override
    public RuleOutcome apply(ViewElement element, RuleContext context) {
        boolean repeatedSegment = NullUtil.isNotNull(element.getMarks())
                && element.getMarks().contains(ElementMark.REPEATED_SEGMENT.name());
        boolean repeatedPage = NullUtil.isNotNull(element.getPage())
                && context.getRepeatedPageNumbers().contains(element.getPage());
        if (!repeatedSegment && !repeatedPage) {
            return RuleOutcome.none();
        }
        String source = repeatedPage ? "组装环节重复页标记" : "组装环节重复段标记";
        String opt = context.getStrategy().action(PreprocessRule.REPEAT, PreprocessAction.EXCLUDE.name());
        return MarkDisposeSupport.dispose(element, opt,
                ViewElementStatus.REPEATED.name(), ViewElementStatus.MARKED_REPEAT.name(), name(),
                new MarkDisposeSupport.TraceTexts(
                        "策略 repeat=EXCLUDE，" + source + "：重复份剔除出检索文本（只保留首份）",
                        "策略 repeat=KEEP，" + source + "：重复份保留（只统计不处置）",
                        "策略 repeat=MARK，" + source + "：重复份标记但保留（仍参与内容流）"));
    }
}
