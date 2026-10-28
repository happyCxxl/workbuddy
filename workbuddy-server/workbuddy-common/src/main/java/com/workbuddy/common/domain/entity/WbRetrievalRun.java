package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索运行记录：四元组（kbId/versionId/ruleId/query）+ 执行时刻结果快照 + 耗时。
 * 评测原始数据账本：勾选对比/跨会话回放按快照回放（集合 append-only 不可重放，快照即证据）。
 * 机器表：append-only、仅 create_time、不挂平台五件套。
 */
@Data
@TableName("wb_knowledge_retrieval_run")
public class WbRetrievalRun {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 知识库 ID */
    private Long kbId;

    /** 索引版本行 ID */
    private Long versionId;

    /** 版本号（快照冗余，展示用） */
    private String versionNo;

    /** 规则行 ID（wb_knowledge_pipeline_strategy_version；引擎基线规则无行 ID 时为 NULL） */
    private Long ruleId;

    /** 规则 name-version（快照冗余，展示用） */
    private String ruleNameVersion;

    /** 查询文本 */
    private String query;

    /** 结果快照（JSON：命中列表全字段） */
    private String resultSnapshot;

    /** 耗时（毫秒） */
    private Integer elapsedMs;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
