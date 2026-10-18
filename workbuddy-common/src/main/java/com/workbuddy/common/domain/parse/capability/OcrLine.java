package com.workbuddy.common.domain.parse.capability;

import com.workbuddy.common.domain.parse.BBox;
import lombok.Data;

/**
 * OCR 文本行（结构化识别结果单元）。
 */
@Data
public class OcrLine {

    /** 行文本 */
    private String text;

    /** 行边界框 */
    private BBox bbox;

    /** 置信度（0~1） */
    private Double confidence;
}
