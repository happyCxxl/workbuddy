package com.workbuddy.common.domain.parse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 页面尺寸（解析环节回填；组装环节构建 UnifiedDocument.pages 的输入）。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageDimension {

    /** 页码（从 1 起） */
    private Integer page;

    /** 页面宽（pt） */
    private Double width;

    /** 页面高（pt） */
    private Double height;
}
