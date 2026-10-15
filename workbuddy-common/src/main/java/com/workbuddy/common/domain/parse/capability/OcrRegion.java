package com.workbuddy.common.domain.parse.capability;

import com.workbuddy.common.domain.parse.BBox;
import lombok.Data;

/**
 * OCR 待识别区域（字段级契约，与网关对齐后冻结）。
 *
 * @author cxxl
 */
@Data
public class OcrRegion {

    /** 图像引用 */
    private String imageRef;

    /** 页码 */
    private Integer page;

    /** 区域边界框 */
    private BBox bbox;
}
