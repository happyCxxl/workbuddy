package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.structure.UnifiedElementType;
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
 * ③ 页眉页脚处置：识别在解析环节（HEADER/FOOTER 元素），本规则按处置方式处理
 * KEEP（保留）/MARK（标记，默认）/EXCLUDE（剔除出检索文本内容流，展示视图完整）。
 */
@Component
public class HeaderFooterRule implements CleanRule {

    /**
     * 规则标识。
     *
     * @return 规则名（策略快照里落库的 name）
     */
    @Override
    public String name() {
        return "header-footer-dispose-v1";
    }

    /**
     * 子步骤展示名。
     *
     * @return 步骤名
     */
    @Override
    public String stepName() {
        return "页眉页脚处置";
    }

    /**
     * 执行顺序。
     *
     * @return 顺序号（小的先执行）
     */
    @Override
    public int order() {
        return 3;
    }

    /**
     * 该规则在策略里是否有配置。
     *
     * @param strategy 预处理策略
     * @return 有配置为 true
     */
    @Override
    public boolean enabledIn(PreprocessStrategy strategy) {
        return NullUtil.isNotNull(strategy.rule(PreprocessRule.HEADER_FOOTER));
    }

    /**
     * 对单个元素执行页眉页脚处置：落在页眉页脚带内且命中通篇重复键时按处置方式处理。
     *
     * @param element 视图元素
     * @param context 规则上下文
     * @return 处置结果
     */
    @Override
    public RuleOutcome apply(ViewElement element, RuleContext context) {
        boolean header = UnifiedElementType.HEADER.name().equals(element.getType());
        boolean footer = UnifiedElementType.FOOTER.name().equals(element.getType());
        if (!header && !footer) {
            return RuleOutcome.none();
        }
        String opt = context.getStrategy().action(PreprocessRule.HEADER_FOOTER, PreprocessAction.MARK.name());
        return MarkDisposeSupport.dispose(element, opt,
                header ? ViewElementStatus.EXCLUDED_HEADER.name() : ViewElementStatus.EXCLUDED_FOOTER.name(),
                header ? ViewElementStatus.MARKED_HEADER.name() : ViewElementStatus.MARKED_FOOTER.name(),
                name(), new MarkDisposeSupport.TraceTexts(
                        "策略 headerFooter=EXCLUDE，剔除出检索文本",
                        "策略 headerFooter=KEEP，保留原样",
                        "解析环节识别的页眉/页脚元素，处置=标记（仍参与内容流）"));
    }
}
