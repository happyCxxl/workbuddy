package com.workbuddy.common.dto.response.index;

import com.workbuddy.common.enums.index.IndexShape;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 索引版本视图（列表与详情共用）：
 * 组合快照结构化展开（文件范围/切片策略/向量策略/形态）+ 状态机 + 统计 + 发布/退役留痕 + 在线标记。
 * 在线口径：wb_knowledge_index_set.currentPublishedVersionId 命中（online=true），与状态值解耦。
 */
@Data
public class IndexVersionVO {

    /** 版本行 ID（wb_knowledge_index_version.id） */
    private Long id;

    /** 版本号（v1、v2…；组合注册序号，集合名 kb_{kbId}_{versionNo} 组成部分） */
    private String versionNo;

    /** 文件范围模式：ALL（全部）/ LIST（指定文件） */
    private String fileScopeMode;

    /** LIST 模式下的文件结果 ID 列表 */
    private List<Long> fileResultIds;

    /** 切片策略 name-version（如 chunk-hybrid-v1） */
    private String chunkStrategy;

    /** 向量策略 name-version（如 embed-default-v1） */
    private String embedStrategy;

    /** 索引形态（当前仅 FULL_VECTOR） */
    private IndexShape shape;

    /** 环节策略映射（stage → 策略 name-version；口径，前端逐环节展示） */
    private Map<String, String> stageStrategies;

    /** 纳入片数 */
    private Integer chunkCount;

    /** 纳入向量数 */
    private Integer vectorCount;

    /** 状态（IndexVersionStatus 枚举名：CREATED/BUILDING/READY/ONLINE/FAILED/RETIRED） */
    private String status;

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

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 是否当前在线发布（二级指针命中） */
    private boolean online;
}
