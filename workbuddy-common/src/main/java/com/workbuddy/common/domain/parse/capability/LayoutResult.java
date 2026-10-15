package com.workbuddy.common.domain.parse.capability;

import lombok.Data;

import java.util.List;

/**
 * 版面分析结果（区域分类 + 阅读顺序）。
 *
 * @author cxxl
 */
@Data
public class LayoutResult {

    /** 版面区域列表 */
    private List<LayoutRegion> regions;

    /** 阅读顺序（区域下标序列） */
    private List<Integer> order;

    /** 模型名 */
    private String model;

    /** 模型版本 */
    private String version;
}
