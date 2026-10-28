package com.workbuddy.common.domain.parse.capability;

import lombok.Data;

import java.util.List;

/**
 * 表格结构识别结果。
 */
@Data
public class TableResult {

    /** 行数 */
    private Integer rows;

    /** 列数 */
    private Integer cols;

    /** 单元格列表 */
    private List<TableCell> cells;

    /** 置信度（0~1） */
    private Double confidence;

    /** 是否跨页 */
    private Boolean crossPage;

    /** 模型名 */
    private String model;

    /** 模型版本 */
    private String version;
}
