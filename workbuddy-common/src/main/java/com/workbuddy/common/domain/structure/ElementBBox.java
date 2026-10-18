package com.workbuddy.common.domain.structure;

import com.workbuddy.common.domain.parse.BBox;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨页元素的分页框：页内片段各记一个边界框（坐标统一为该页左上角原点 + pt）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ElementBBox {

    /** 页码 */
    private Integer page;

    /** 该页内片段边界框 */
    private BBox bbox;
}
