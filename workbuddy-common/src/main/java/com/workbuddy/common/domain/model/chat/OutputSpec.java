package com.workbuddy.common.domain.model.chat;

import lombok.Data;

/**
 * 输出约束：格式 + 可选 JSON Schema 文本；为空按 {@link OutputFormat#TEXT} 处理。
 */
@Data
public class OutputSpec {

    /** 输出格式，空按 TEXT 处理 */
    private OutputFormat format = OutputFormat.TEXT;

    /** JSON Schema 文本，仅 JSON_SCHEMA 必填 */
    private String schema;
}
