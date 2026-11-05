package com.workbuddy.common.enums.modelgateway;

/**
 * 模型能力业务场景键：提示词与 schema 在灵犀 prompts/schemas 目录按业务统一管理，
 * Java 侧只在此登记键、不写死文案。新增业务 = 加一个常量 + 灵犀加同名模板/schema 文件
 * （登记表见 lingxi 侧 prompts/README.md 与 schemas/README.md）。
 */
public enum ModelScenarioKey {

    /** OCR 行识别（解析环节扫描/乱码页补文字） */
    OCR_LINES("ocr_lines", "ocr_lines"),

    /** 版面分析（复杂页阅读顺序补强） */
    LAYOUT_ANALYZE("layout_analyze", "layout"),

    /** 表格结构识别（规则失败表格补强） */
    TABLE_STRUCTURE("table_structure", "table");

    /** 业务提示词键（灵犀 prompts 目录文件名） */
    private final String promptKey;

    /** 业务 schema 键（灵犀 schemas 目录文件名；无结构化输出时为 null） */
    private final String schemaKey;

    /**
     * 构造业务场景键。
     *
     * @param promptKey 提示词键
     * @param schemaKey schema 键（可空）
     */
    ModelScenarioKey(String promptKey, String schemaKey) {
        this.promptKey = promptKey;
        this.schemaKey = schemaKey;
    }

    /**
     * 业务提示词键。
     *
     * @return 提示词键
     */
    public String promptKey() {
        return promptKey;
    }

    /**
     * 业务 schema 键。
     *
     * @return schema 键；无结构化输出为 null
     */
    public String schemaKey() {
        return schemaKey;
    }
}
