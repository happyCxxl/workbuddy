package com.workbuddy.worker.structure.impl.title;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.structure.title.TitleDecision;
import com.workbuddy.worker.structure.title.TitleRule;
import com.workbuddy.worker.structure.title.TitleRuleContext;
import org.springframework.stereotype.Component;

/**
 * 字号/加粗启发式标题规则（最弱层）：短句 + 加粗 + 字号 ≥ 文档中位数 × 配置比例（默认 1.15）→ 二级档
 * （深层标题靠编号模式，本层只兜无编号场景）。
 */
@Component
public class FontSignalTitleRule implements TitleRule {

    @Override
    public int order() {
        return 60;
    }

    @Override
    public TitleDecision tryMatch(TitleRuleContext context) {
        Double size = context.fontSize();
        if (!context.shortText() || !context.bold() || NullUtil.isNull(size)
                || context.medianSize() <= 0
                || size < context.medianSize() * context.properties().getFontSignalSizeRatio()) {
            return null;
        }
        return TitleDecision.title(2,
                TitleDecision.evidence("font-signal", size, true, null));
    }
}
