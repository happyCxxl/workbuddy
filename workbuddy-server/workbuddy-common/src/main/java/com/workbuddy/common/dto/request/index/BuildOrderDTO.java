package com.workbuddy.common.dto.request.index;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 索引构建请求。
 *
 * <p>所属知识库取自路径参数，请求体里的同名字段不采用。
 */
@Data
public class BuildOrderDTO {

    /** 所属知识库（由路径参数回填） */
    private Long knowledgeBaseId;

    /** 组合快照：文件范围 × 索引形态 × 环节策略映射 */
    @Valid
    @NotNull(message = "组合快照不能为空")
    private ComboSnapshotDTO comboSnapshot;

    /** 触发类型（IndexBuildTrigger 枚举名） */
    private String trigger;
}
