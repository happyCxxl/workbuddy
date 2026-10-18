package com.workbuddy.common.domain.rules;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;

import java.util.Map;

/**
 * 环节漏斗规则：文件结果列表按环节过滤时，"到达某环节" = 存在该环节上游的成功产物。
 *
 * <p>映射关系是处理链环节依赖的领域知识（PARSE→STRUCTURE→PREPROCESS→CHUNK→EMBED）。
 */
public final class StageFunnelRules {

    /** 本环节 → 上游成功产物环节（PARSE 页与缺省不过滤） */
    private static final Map<String, String> STAGE_UPSTREAM = Map.of(
            "STRUCTURE", PipelineStage.PARSE.name(),
            "PREPROCESS", PipelineStage.STRUCTURE.name(),
            "CHUNK", PipelineStage.PREPROCESS.name(),
            "EMBED", PipelineStage.CHUNK.name());

    private StageFunnelRules() {
    }

    /**
     * 解析漏斗过滤目标：返回该环节的"上游成功产物环节"。
     *
     * @param stage 环节（PipelineStage 枚举名）
     * @return 上游成功产物环节名（查询只保留存在该环节成功产物的文件）；PARSE/空返回 null（不过滤）
     * @throws KnowledgeException 未知环节（PARAM_INVALID 40001）
     */
    public static String resolveUpstreamStage(String stage) {
        if (StrUtil.isBlank(stage) || PipelineStage.PARSE.name().equals(stage)) {
            return null;
        }
        ThrowUtil.throwIf(!STAGE_UPSTREAM.containsKey(stage), ErrorCode.PARAM_INVALID, "未知环节: " + stage);
        return STAGE_UPSTREAM.get(stage);
    }
}
