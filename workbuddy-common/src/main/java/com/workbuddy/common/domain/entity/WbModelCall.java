package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型调用明细行（表：wb_model_call）：一次尝试一行，成功失败都记，只插不改。
 */
@Data
@TableName("wb_model_call")
public class WbModelCall {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一次业务请求的追踪 ID（同一次请求的多次尝试共用） */
    private String requestId;

    /** 处理链任务 ID，可空 */
    private Long taskId;

    /** 能力（ModelCapability 名称） */
    private String capability;

    /** 厂商码 */
    private String vendorCode;

    /** 厂商侧模型名 */
    private String modelKey;

    /** 路由原因（RouteReason 名称） */
    private String routeReason;

    /** 本次请求内的尝试序号（1 起） */
    private Integer attempt;

    /** 状态：SUCCESS / FAILED */
    private String status;

    /** 失败时的错误码 */
    private Integer errorCode;

    /** 本模型调用耗时（毫秒） */
    private Integer latencyMs;

    /** 输入 token（usage 回填，可空） */
    private Integer promptTokens;

    /** 输出 token（usage 回填，可空） */
    private Integer completionTokens;

    /** 创建时间 */
    private LocalDateTime createTime;
}
