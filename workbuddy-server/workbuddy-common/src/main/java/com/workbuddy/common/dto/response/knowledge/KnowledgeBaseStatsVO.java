package com.workbuddy.common.dto.response.knowledge;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库统计视图对象（仅统计未删除数据）。
 *
 * <p>三个数**同一可见范围**：普通用户统计自己创建的知识库（及其下文档），管理员统计全部。
 * 它不是"平台资产"而是"当前用户可见的资产"。
 */
@Data
public class KnowledgeBaseStatsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 可见范围内知识库总数（含已停用） */
    private Long knowledgeBaseCount;

    /** 其中启用中的知识库数（status=1） */
    private Long enabledCount;

    /** 可见库下的文档总数：wb_file_result 记录数（一次提交 = 一个任务 = 一行） */
    private Long documentCount;
}
