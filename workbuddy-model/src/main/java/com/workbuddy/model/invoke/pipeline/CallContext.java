package com.workbuddy.model.invoke.pipeline;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.ModelCapability;
import lombok.Data;

import java.util.List;

/**
 * 一次模型调用的上下文：请求信息、候选、尝试序号、执行回调与结果。
 */
@Data
public class CallContext {

    /** 一次业务请求的追踪 ID（同一次请求的多次尝试共用） */
    private String requestId;

    /** 所需能力 */
    private ModelCapability capability;

    /** 精确模型名过滤（向量化策略带模型名时使用），可空 */
    private String modelKeyFilter;

    /** 有序候选（由候选阶段填充） */
    private List<ModelDescriptor> candidates = List.of();

    /** 已尝试次数（每次真实调用 +1） */
    private int attempt;

    /** 最近一次失败（已分类），可空 */
    private RuntimeException lastError;

    /** 本次调用的执行回调 */
    private ModelCall<?> call;

    /** 成功结果 */
    private Object result;
}
