package com.workbuddy.common.dto.response.input;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提交日志 VO：kb_submit_log 的对外视图（提交记录列表/提交响应用）。
 * 仅映射展示所需字段；id 类字段由全局 Long 序列化口径转字符串（见 LongIdJsonConfig），防前端 JS 精度丢失。
 *
 * @author cxxl
 */
@Data
public class SubmitLogVO {

    /** 主键 */
    private Long id;

    /** 目标知识库 */
    private Long knowledgeBaseId;

    /** 幂等键（回显，前端凭此识别同一请求） */
    private String requestId;

    /** 文件 ID */
    private String fileId;

    /** 文件指纹 */
    private String sha256;

    /** 文件名 */
    private String fileName;

    /** 文件结果 ID（失败时为空） */
    private Long fileResultId;

    /** 提交结果：PASS / FAIL */
    private String status;

    /** 失败原因（PASS 时为空） */
    private String failReason;

    /** 提交时间 */
    private LocalDateTime createTime;
}
