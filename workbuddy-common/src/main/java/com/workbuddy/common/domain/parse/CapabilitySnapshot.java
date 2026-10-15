package com.workbuddy.common.domain.parse;

import lombok.Data;

/**
 * 能力快照：本次解析实际使用的组件/模型版本（可复现）。
 * ocr/layout/table 为 null 时显式声明"未接该能力"。
 *
 * @author cxxl
 */
@Data
public class CapabilitySnapshot {

    /** 原生解析器名称（如 pdfbox） */
    private String parserName;

    /** 原生解析器版本（如 3.0.4） */
    private String parserVersion;

    /** OCR 能力（预留；null = 未接） */
    private CapabilityRef ocr;

    /** 版面分析能力（预留；null = 未接） */
    private CapabilityRef layout;

    /** 表格识别能力（预留；null = 未接） */
    private CapabilityRef table;

    /** 能力引用（模型名 + 版本） */
    @Data
    public static class CapabilityRef {
        private String model;
        private String version;
    }
}
