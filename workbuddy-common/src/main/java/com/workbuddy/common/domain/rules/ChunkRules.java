package com.workbuddy.common.domain.rules;

/**
 * 切片规则：biz 控制面与执行器共用的契约常量。
 *
 * @author cxxl
 */
public final class ChunkRules {

    /** 上游预处理产物缺失提示（触发校验与执行兜底共用，勿改文案） */
    public static final String UPSTREAM_PREPROCESS_MISSING = "预处理视图产物不存在，请先触发预处理";

    private ChunkRules() {
    }
}
