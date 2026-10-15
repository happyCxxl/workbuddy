package com.workbuddy.common.domain.model;

import lombok.Data;

/**
 * chat 与 vision 共用出参：原始文本 + 解析后的通用树 + 用量 + 模型标识。
 *
 * @author cxxl
 */
@Data
public class GenerationResult {

    /** 原始文本 */
    private String content;

    /** 解析后的通用树（可空；非 JSON 输出或解析不出时为 null） */
    private Object json;

    /** 用量（厂商未回即 null） */
    private ModelUsage usage;

    /** 响应回传的模型标识 */
    private String model;

    /** 响应回传的模型版本 */
    private String modelVersion;
}
