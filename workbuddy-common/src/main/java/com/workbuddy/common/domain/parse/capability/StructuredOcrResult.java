package com.workbuddy.common.domain.parse.capability;

import lombok.Data;

import java.util.List;

/**
 * 结构化 OCR 识别结果。
 *
 * @author cxxl
 */
@Data
public class StructuredOcrResult {

    /** 文本行列表 */
    private List<OcrLine> lines;

    /** 模型名 */
    private String model;

    /** 模型版本 */
    private String version;
}
