package com.workbuddy.worker.structure;

import lombok.Data;

/**
 * 组装上下文：任务标识与阈值配置引用（组装管线入参）。
 *
 * @author cxxl
 */
@Data
public class AssembleContext {

    /** 处理链任务 ID（链路追踪用） */
    private Long taskId;

    /** 文件结果 ID（血缘起点） */
    private Long fileResultId;

    /** 原始文件类型（MIME；Excel sheet→SECTION 判定用） */
    private String sourceFileType;

    /** 组装阈值配置 */
    private StructureProperties properties;
}
