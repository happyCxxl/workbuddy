package com.workbuddy.worker.preprocessing.impl.rule;

import com.workbuddy.worker.preprocessing.impl.TextBase;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.preprocessing.rule.CleanRule;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * ① 编码规范化（基础层，默认开，策略可关）：字符级整理产出 displayText（空白/换行/乱码）
 * 与 normalizedText 基座（+Unicode NFKC 全角→半角）；表格单元格同样处理。
 * 原文 rawText 永不动。
 */
@Component
public class EncodingCleanRule implements CleanRule {

    @Override
    public String name() {
        return "encoding-clean-v1";
    }

    @Override
    public String stepName() {
        return "编码规范化";
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public boolean enabledIn(PreprocessStrategy strategy) {
        return strategy.enabled(PreprocessRule.ENCODING, true);
    }

    @Override
    public RuleOutcome apply(ViewElement element, RuleContext context) {
        PreprocessProperties properties = context.getProperties();
        boolean changed = false;
        String displayAfter = null;
        String raw = element.getRawText();
        if (StrUtil.isNotBlank(raw)) {
            String display = TextBase.tidyDisplay(raw);
            element.setDisplayText(display);
            element.setNormalizedText(TextBase.normalizeBase(display));
            changed = !Objects.equals(raw, display);
            displayAfter = display;
        }
        if (NullUtil.isNotNull(element.getCells())) {
            for (ViewCell cell : element.getCells()) {
                if (StrUtil.isNotBlank(cell.getText())) {
                    String display = TextBase.tidyDisplay(cell.getText());
                    cell.setNormalizedText(TextBase.normalizeBase(display));
                    changed |= !Objects.equals(cell.getText(), display);
                }
            }
        }
        if (!changed) {
            return RuleOutcome.none();
        }
        TraceEntry trace = TraceEntry.of(name(), null, TraceEntry.ACTION_REPLACE,
                StrUtil.maxLength(raw, properties.getTraceBeforeAfterMaxLen()),
                StrUtil.maxLength(displayAfter, properties.getTraceBeforeAfterMaxLen()),
                "字符级整理：控制字符/全角空格→半角、空白折叠、未定义码点剔除、Unicode NFKC");
        return RuleOutcome.hit(trace, 1);
    }
}
