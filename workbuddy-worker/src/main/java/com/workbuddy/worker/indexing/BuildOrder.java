package com.workbuddy.worker.indexing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 索引构建命令：biz 生成、worker 消费（随 BUILD_INDEX 任务快照下发）。
 */
@Data
public class BuildOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属知识库 */
    private Long knowledgeBaseId;

    /** 组合快照 */
    private ComboSnapshot comboSnapshot;

    /** 触发类型（IndexBuildTrigger 枚举名） */
    private String trigger;
}
