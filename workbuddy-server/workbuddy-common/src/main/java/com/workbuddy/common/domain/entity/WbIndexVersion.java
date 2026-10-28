package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 索引版本行：组合快照 + 状态机 + 发布/退役留痕。
 * 机器表：append-only、仅 create_time、不挂平台五件套。
 */
@Data
@TableName("wb_knowledge_index_version")
public class WbIndexVersion {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属索引集合 */
    private Long indexSetId;

    /** 版本号（v1、v2…；组合注册序号，集合名 kb_{kbId}_{versionNo} 组成部分） */
    private String versionNo;

    /** 组合快照（JSON 文本：fileScopeMode/fileResultIds/shape/stageStrategies） */
    private String comboSnapshot;

    /** 纳入片数 */
    private Integer chunkCount;

    /** 纳入向量数 */
    private Integer vectorCount;

    /** 状态（IndexVersionStatus 枚举名） */
    private String status;

    /** 默认检索规则行 ID（wb_knowledge_pipeline_strategy_version.id，type=RETRIEVAL；空 → kb 默认 → 引擎基线） */
    private Long defaultRuleId;

    /** 失败原因（完整性缺口/维度不一致/一致性失败/写失败） */
    private String buildError;

    /** 构建任务 ID（wb_knowledge_pipeline_task，stage=BUILD_INDEX） */
    private Long taskId;

    /** 验证通过时间 */
    private LocalDateTime validatedAt;

    /** 发布时间 */
    private LocalDateTime publishedAt;

    /** 发布人 */
    private String publishedBy;

    /** 退役时间 */
    private LocalDateTime retiredAt;

    /** 退役人 */
    private String retiredBy;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
