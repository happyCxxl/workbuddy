package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.PreprocessAction;
import com.workbuddy.common.enums.preprocess.PreprocessParam;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.preprocessing.rule.CleanRule;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;
import org.springframework.stereotype.Component;

/**
 * ④ 目录处置：识别在解析环节（TOC_LINE 行级特征），本规则做"整页判目录页"聚合
 * （PDF：该页 TOC_LINE 元素数 ≥ tocMinLinesPerPage；Word：连续窗口）后按处置方式处理。
 * 非目录页的特征行不受影响（防误删）。
 */
@Component
public class TocRule implements CleanRule {

    @Override
    public String name() {
        return "toc-dispose-v1";
    }

    @Override
    public String stepName() {
        return "目录处置";
    }

    @Override
    public int order() {
        return 4;
    }

    @Override
    public boolean enabledIn(PreprocessStrategy strategy) {
        return NullUtil.isNotNull(strategy.rule(PreprocessRule.TOC));
    }

    @Override
    public RuleOutcome apply(ViewElement element, RuleContext context) {
        if (NullUtil.isNull(element.getMarks()) || !element.getMarks().contains(ElementMark.TOC_LINE.name())) {
            return RuleOutcome.none();
        }
        boolean tocPage;
        String evidence;
        if (NullUtil.isNotNull(element.getPage())) {
            int count = context.getTocLineCountByPage().getOrDefault(element.getPage(), 0);
            int minLinesPerPage = context.getStrategy()
                    .intParam(PreprocessRule.TOC, PreprocessParam.TOC_MIN_LINES_PER_PAGE,
                            context.getProperties().getTocMinLinesPerPage());
            tocPage = count >= minLinesPerPage;
            evidence = "整页判为目录页（该页 TOC_LINE " + count + " 行，阈值 " + minLinesPerPage + "）";
        } else {
            tocPage = context.getTocRunElementIds().contains(element.getElementId());
            evidence = "连续 TOC_LINE 窗口（Word 无页概念）";
        }
        if (!tocPage) {
            return RuleOutcome.none();
        }
        String opt = context.getStrategy().action(PreprocessRule.TOC, PreprocessAction.MARK.name());
        return MarkDisposeSupport.dispose(element, opt,
                ViewElementStatus.EXCLUDED_TOC.name(), ViewElementStatus.MARKED_TOC.name(), name(),
                new MarkDisposeSupport.TraceTexts(
                        "策略 toc=EXCLUDE，剔除出检索文本；" + evidence,
                        "策略 toc=KEEP，保留原样；" + evidence,
                        evidence));
    }
}
