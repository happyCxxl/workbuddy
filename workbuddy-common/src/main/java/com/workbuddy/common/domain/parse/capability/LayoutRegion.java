package com.workbuddy.common.domain.parse.capability;

import com.workbuddy.common.domain.parse.BBox;
import lombok.Data;

/**
 * 版面区域（区域分类 + 位置 + 置信度）。
 */
@Data
public class LayoutRegion {

    /** 区域类型（title/text/table/figure/header/footer/stamp…） */
    private String type;

    /** 区域边界框 */
    private BBox bbox;

    /** 置信度（0~1） */
    private Double confidence;
}
