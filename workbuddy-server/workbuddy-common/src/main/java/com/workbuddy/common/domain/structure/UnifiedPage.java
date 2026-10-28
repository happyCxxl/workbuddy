package com.workbuddy.common.domain.structure;

import lombok.Data;

/**
 * 页面基准：全部坐标的统一基准"左上角原点 + pt"。XLS/XLSX 无页概念，pages[] 为空。
 */
@Data
public class UnifiedPage {

    /** 页面 ID */
    private String pageId;

    /** 页码（从 1 起） */
    private Integer pageNumber;

    /** 页面宽（pt） */
    private Double width;

    /** 页面高（pt） */
    private Double height;

    /** 旋转角度（预留未实现：当前恒 0，PDF /Rotate 未接；接入时由解析侧回填） */
    private Double rotation;

    /** 单位 */
    private String unit;

    /** 坐标原点 */
    private String origin;

    /** 页面级标记（PageMark 枚举名；识别环节写入、处置环节读取） */
    private java.util.List<String> marks;
}
