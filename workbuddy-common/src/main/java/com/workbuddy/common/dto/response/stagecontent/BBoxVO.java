package com.workbuddy.common.dto.response.stagecontent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 元素边界框（原文预览画高亮用）：单位点（pt）、左上角原点，与解析产物里的 bbox 同口径。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BBoxVO {

    /** 左上角横坐标 */
    private Double x;

    /** 左上角纵坐标 */
    private Double y;

    /** 宽度 */
    private Double width;

    /** 高度 */
    private Double height;
}
