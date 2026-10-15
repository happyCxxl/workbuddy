package com.workbuddy.common.enums.parse;

/**
 * 解析子步骤名目录（落 wb_knowledge_pipeline_step_log.step_name 的固定口径）。
 * 落库值为中文展示值（value），前端详情页直接渲染。
 *
 * @author cxxl
 */
public enum ParseStepName {

    /** 文件级路由：按 MIME 选择解析器 */
    FILE_ROUTE("文件级路由"),

    /** 原生解析：POI/PDFBox 提取元素与页级指标 */
    NATIVE_PARSE("原生解析"),

    /** 质量检查：信号判定 + 内置降级 + 成功占比门槛评估 */
    QUALITY_CHECK("质量检查");

    private final String value;

    ParseStepName(String value) {
        this.value = value;
    }

    /** 落库值（展示口径） */
    public String value() {
        return value;
    }
}
