package com.workbuddy.common.domain.model;

import lombok.Data;

/**
 * 输出约束：format 决定校验口径；schema 是 JSON Schema 文本，由业务侧写。
 *
 * @author cxxl
 */
@Data
public class OutputSpec {

    /** 输出格式：TEXT / JSON_OBJECT / JSON_SCHEMA */
    private OutputFormat format = OutputFormat.TEXT;

    /** JSON Schema 文本，仅 JSON_SCHEMA 必填（可空） */
    private String schema;
}
