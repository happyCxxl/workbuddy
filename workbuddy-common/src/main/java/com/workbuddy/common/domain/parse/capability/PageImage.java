package com.workbuddy.common.domain.parse.capability;

import lombok.Data;

/**
 * 页面图像（版面分析输入）。
 *
 * @author cxxl
 */
@Data
public class PageImage {

    /** 页码 */
    private Integer page;

    /** 图像引用 */
    private String imageRef;

    /** DPI */
    private Integer dpi;

    /** 宽（pt） */
    private Double width;

    /** 高（pt） */
    private Double height;
}
