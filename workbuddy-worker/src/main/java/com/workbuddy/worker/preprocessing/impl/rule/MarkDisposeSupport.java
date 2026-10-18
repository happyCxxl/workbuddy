package com.workbuddy.worker.preprocessing.impl.rule;

import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.PreprocessAction;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.worker.preprocessing.ViewElementHelper;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;

/**
 * 标记类规则的三档处置（package-private）：剔除 / 保留 / 标记（默认）。
 * 三档都会落一条追踪；剔除档同时清检索文本与表格单元格文本。状态名与追踪文案由各规则传入。
 */
final class MarkDisposeSupport {

    private MarkDisposeSupport() {
    }

    /**
     * 三档处置：EXCLUDE → 置剔除态并清检索文本（changed=1）；KEEP → 置 NORMAL；其余（含未配置）→ 置标记态。
     *
     * @param element        目标元素
     * @param option         策略处置值（EXCLUDE / KEEP / 其他一律按 MARK）
     * @param excludedStatus 剔除档状态名
     * @param markedStatus   标记档状态名
     * @param ruleName       规则名（写追踪来源）
     * @param texts          三档追踪文案
     */
    static RuleOutcome dispose(ViewElement element, String option, String excludedStatus, String markedStatus,
                               String ruleName, TraceTexts texts) {
        TraceEntry trace;
        int changed = 0;
        if (PreprocessAction.EXCLUDE.name().equalsIgnoreCase(option)) {
            element.setStatus(excludedStatus);
            element.setNormalizedText(null);
            // 表格类元素的单元格文本也要清：不清会残留进检索内容流
            ViewElementHelper.clearCellTexts(element);
            trace = TraceEntry.of(ruleName, null, TraceEntry.ACTION_EXCLUDE, null, null, texts.exclude());
            changed = 1;
        } else if (PreprocessAction.KEEP.name().equalsIgnoreCase(option)) {
            element.setStatus(ViewElementStatus.NORMAL.name());
            trace = TraceEntry.of(ruleName, null, TraceEntry.ACTION_KEEP, null, null, texts.keep());
        } else {
            element.setStatus(markedStatus);
            trace = TraceEntry.of(ruleName, null, TraceEntry.ACTION_MARK, null, null, texts.mark());
        }
        return RuleOutcome.hit(trace, changed);
    }

    /** 三档追踪文案（EXCLUDE / KEEP / MARK），由各规则按自己的参数名与佐证拼好 */
    record TraceTexts(String exclude, String keep, String mark) {
    }
}
