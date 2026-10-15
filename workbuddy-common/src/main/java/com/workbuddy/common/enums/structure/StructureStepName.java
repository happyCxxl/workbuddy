package com.workbuddy.common.enums.structure;

/**
 * 组装子步骤名目录（落 wb_knowledge_pipeline_step_log.step_name 的固定口径，对齐解析环节的 ParseStepName）。
 * 落库值为中文展示值（value），前端详情页直接渲染。
 *
 * @author cxxl
 */
public enum StructureStepName {

    /** 元素标准化：解析元素 → 统一元素（类型映射 / 全局 ID / 扩展区透传） */
    NORMALIZE("元素标准化"),

    /** 去重与阅读顺序：跨来源去重合并 + XY-cut 阅读顺序 */
    DEDUP_ORDER("去重与阅读顺序"),

    /** 结构组装与接续：标题推定 + 章节与关系 + 跨页续表接续 */
    ASSEMBLE_CONTINUATION("结构组装与接续"),

    /** 溯源校验：可回溯占比统计（组装报告） */
    VERIFY_PROVENANCE("溯源校验"),

    /** 重复与噪声识别：重复页 / 重复段 / 噪声页标记 */
    MARK_REPEAT_NOISE("重复与噪声识别");

    private final String value;

    StructureStepName(String value) {
        this.value = value;
    }

    /** 落库值（展示口径） */
    public String value() {
        return value;
    }
}
