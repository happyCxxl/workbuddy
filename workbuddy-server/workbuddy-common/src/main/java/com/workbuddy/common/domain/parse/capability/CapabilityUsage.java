package com.workbuddy.common.domain.parse.capability;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 本次解析实际用到的能力引用：能力类别 + 模型名 + 版本，用于回填产物的能力快照。
 * 用普通类而非 record：本对象随解析产物 JSON 落库并反序列化，hutool 对 record 不支持（会丢类型）。
 */
@Data
@NoArgsConstructor
public class CapabilityUsage {

    /** 结构化 OCR */
    public static final String KIND_OCR = "ocr";

    /** 版面分析 */
    public static final String KIND_LAYOUT = "layout";

    /** 表格结构识别 */
    public static final String KIND_TABLE = "table";

    /** 能力类别 */
    private String kind;

    /** 模型名 */
    private String model;

    /** 模型版本 */
    private String version;

    /**
     * 构造一次能力使用记录。
     *
     * @param kind    能力类别
     * @param model   模型名
     * @param version 模型版本
     */
    public CapabilityUsage(String kind, String model, String version) {
        this.kind = kind;
        this.model = model;
        this.version = version;
    }

    /**
     * 表格结构识别的一次使用记录。
     *
     * @param model   模型标识
     * @param version 模型版本
     * @return 使用记录
     */
    public static CapabilityUsage table(String model, String version) {
        return new CapabilityUsage(KIND_TABLE, model, version);
    }

    /**
     * 是否属于给定类别。
     *
     * @param expected 类别标识
     * @return 类别相同为 true
     */
    public boolean isKind(String expected) {
        return expected.equals(kind);
    }
}
