package com.workbuddy.common.dto.response.lineage;

import lombok.Data;

/**
 * 能力快照（执行树节点展示用）：由 {@code product.capabilitySnapshot} 的 JSON 文本解析成对象下发。
 *
 * <p>字段与 {@link com.workbuddy.common.domain.parse.CapabilitySnapshot} 一一对应；
 * {@code ocr/layout/table} 为预留能力，未接入时三者均为 null（与领域对象口径一致：
 * null 显式表示"未接该能力"，而不是"未知"）。引用以 {@link CapabilityRefVO} 表达。
 *
 * @author cxxl
 */
@Data
public class LineageCapabilityVO {

    /** 原生解析器名称（如 pdfbox） */
    private String parserName;

    /** 原生解析器版本（如 3.0.4） */
    private String parserVersion;

    /** OCR 能力（预留；null = 未接） */
    private CapabilityRefVO ocr;

    /** 版面分析能力（预留；null = 未接） */
    private CapabilityRefVO layout;

    /** 表格识别能力（预留；null = 未接） */
    private CapabilityRefVO table;

    /**
     * 能力引用（模型名 + 版本）。
     */
    @Data
    public static class CapabilityRefVO {

        private String model;

        private String version;
    }
}
