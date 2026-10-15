package com.workbuddy.common.domain.model;

import lombok.Data;

/**
 * 图像区域（bbox，可空）：页码 + 四点坐标。
 *
 * @author cxxl
 */
@Data
public class ImageRegion {

    /** 页码 */
    private Integer pageNo;

    /** 左上角 x */
    private Double x0;

    /** 左上角 y */
    private Double y0;

    /** 右下角 x */
    private Double x1;

    /** 右下角 y */
    private Double y1;
}
