package com.workbuddy.worker.structure.title;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.structure.ElementExtensionKey;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.structure.StructureProperties;

/**
 * 标题判定上下文：候选元素 + 文档字号基准（中位数）+ 阈值配置。
 * 规则链内共享的派生口径（短句/样式/字号佐证）收敛在此，规则自身不重复计算。
 *
 * @param element    候选元素
 * @param medianSize 文档字号中位数
 * @param properties 结构化阈值配置
 */
public record TitleRuleContext(UnifiedElement element, double medianSize, StructureProperties properties) {

    /**
     * 候选文本（trim 后）。
     *
     * @return 文本
     */
    public String text() {
        return StrUtil.trim(element.getText());
    }

    /**
     * 候选文本是否短句（标题候选长度上限内，防编号误判）。
     *
     * @return 在上限内为 true
     */
    public boolean shortText() {
        return text().length() <= properties.getTitleCandidateMaxLength();
    }

    /**
     * 原生样式（Heading1~9 等）。
     *
     * @return 样式名；没有时为 null
     */
    public String style() {
        if (NullUtil.isNull(element.getExtension())) {
            return null;
        }
        Object value = element.getExtension().get(ElementExtensionKey.STYLE.key());
        return NullUtil.isNull(value) ? null : String.valueOf(value);
    }

    /**
     * 字号。
     *
     * @return 字号；没有时为 null
     */
    public Double fontSize() {
        return NullUtil.isNotNull(element.getFont()) ? element.getFont().getSize() : null;
    }

    /**
     * 是否加粗。
     *
     * @return 加粗为 true
     */
    public boolean bold() {
        return NullUtil.isNotNull(element.getFont()) && Boolean.TRUE.equals(element.getFont().getBold());
    }

    /**
     * 字号佐证：加粗，或字号不低于文档中位数乘以配置比例（默认 1.05）。
     *
     * @return 有字号佐证为 true
     */
    public boolean fontBacked() {
        return bold() || (NullUtil.isNotNull(fontSize()) && medianSize > 0
                && fontSize() >= medianSize * properties.getFontBackedSizeRatio());
    }
}
