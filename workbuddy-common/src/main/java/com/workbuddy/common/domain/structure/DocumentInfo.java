package com.workbuddy.common.domain.structure;

import lombok.Data;

/**
 * 文档信息：血缘起点（documentId/sourceFileRef/parserRunId）+ schema 版本 + 能力快照。
 *
 * @author cxxl
 */
@Data
public class DocumentInfo {

    /** 全局唯一文档 ID（doc- 前缀 + fileResultId） */
    private String documentId;

    /** 原始文件引用（file-manager 文件 ID） */
    private String sourceFileRef;

    /** 原始文件类型（MIME） */
    private String sourceFileType;

    /** 解析运行 ID（= fileResultId，血缘链：结构 ↔ 解析记录 ↔ 原文件） */
    private Long parserRunId;

    /** 结构 schema 语义化版本（字段只增不删不改） */
    private String schemaVersion;

    /** 本次解析组件/模型版本快照 */
    private Object capabilitySnapshot;
}
