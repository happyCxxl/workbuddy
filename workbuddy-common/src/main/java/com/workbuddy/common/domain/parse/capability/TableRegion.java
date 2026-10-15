package com.workbuddy.common.domain.parse.capability;

import com.workbuddy.common.domain.parse.BBox;
import lombok.Data;

/**
 * 表格识别区域（表格结构识别输入）。
 *
 * @author cxxl
 */
@Data
public class TableRegion {

    /** 图像引用 */
    private String imageRef;

    /** 页码 */
    private Integer page;

    /** 区域边界框 */
    private BBox bbox;

    /** 可选 OCR 文本层 */
    private String ocrText;
}
