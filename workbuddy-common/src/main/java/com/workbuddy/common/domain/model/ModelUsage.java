package com.workbuddy.common.domain.model;

import lombok.Data;

/**
 * 用量：三项计数在厂商未回 usage 时都为 null。
 *
 * <p>字段由 LC4j 的 {@code TokenUsage} 映射而来，字段类型与它对齐为 {@link Integer}，未知为 null。
 *
 * @author cxxl
 */
@Data
public class ModelUsage {

    /** LC4j 的 inputTokenCount：提示词 token 数（可空） */
    private Integer promptTokens;

    /** LC4j 的 outputTokenCount：生成 token 数（可空） */
    private Integer completionTokens;

    /** LC4j 的 totalTokenCount：总 token 数（可空） */
    private Integer totalTokens;
}
