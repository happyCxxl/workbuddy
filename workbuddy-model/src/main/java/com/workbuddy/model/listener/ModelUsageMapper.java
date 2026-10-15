package com.workbuddy.model.listener;

import com.workbuddy.common.domain.model.ModelUsage;
import com.workbuddy.common.utils.NullUtil;
import dev.langchain4j.model.output.TokenUsage;

/**
 * 用量映射：LC4j 的 {@link TokenUsage} 三项转成 {@link ModelUsage} 三项，适配器与监听器共用本处一份实现。
 *
 * @author cxxl
 */
public final class ModelUsageMapper {

    private ModelUsageMapper() {
    }

    /**
     * 映射：入参为空即返回 null（厂商未回 usage），单项缺失留 null。
     *
     * @param tokenUsage LC4j 响应用量
     * @return 我们的用量结构
     */
    public static ModelUsage toModelUsage(TokenUsage tokenUsage) {
        if (NullUtil.isNull(tokenUsage)) {
            return null;
        }
        ModelUsage usage = new ModelUsage();
        usage.setPromptTokens(tokenUsage.inputTokenCount());
        usage.setCompletionTokens(tokenUsage.outputTokenCount());
        usage.setTotalTokens(tokenUsage.totalTokenCount());
        return usage;
    }
}
