package com.workbuddy.common.domain.parse.capability;

import lombok.Data;

import java.util.function.Function;

/**
 * 能力调用上下文（预留：请求 ID、超时/重试参数等随网关接入补充）。
 */
@Data
public class ProviderContext {

    /** 处理链任务 ID（链路追踪用） */
    private Long taskId;

    /** 资产字节解析入口：imageRef → 字节（biz 注入对象存储读回实现；worker 不直接碰存储） */
    private Function<String, byte[]> assetBytes;
}
