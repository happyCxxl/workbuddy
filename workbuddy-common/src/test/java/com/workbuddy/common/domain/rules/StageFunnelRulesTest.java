package com.workbuddy.common.domain.rules;

import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 环节漏斗规则单测。
 *
 * <p>守护 {@code resolveUpstreamStage} 的映射与兜底：列表按环节过滤时，
 * 「到达某环节」等于「存在其上游成功产物」，映射写错会让列表多出或漏掉文件。
 */
class StageFunnelRulesTest {

    /**
     * PARSE 与空白不过滤（返回 null）。
     *
     * <p>守护 {@code resolveUpstreamStage} 的首环节与空参兜底：把 PARSE 也过滤会一条都查不到。
     */
    @Test
    void resolveUpstreamStageShouldReturnNullForParseOrBlank() {
        assertNull(StageFunnelRules.resolveUpstreamStage(null));
        assertNull(StageFunnelRules.resolveUpstreamStage(" "));
        assertNull(StageFunnelRules.resolveUpstreamStage(PipelineStage.PARSE.name()));
    }

    /**
     * 四个下游环节映射到各自上游。
     *
     * <p>守护 {@code resolveUpstreamStage} 的链路映射：错一环会让漏斗过滤错产物。
     */
    @Test
    void resolveUpstreamStageShouldMapChainOrder() {
        assertEquals(PipelineStage.PARSE.name(),
                StageFunnelRules.resolveUpstreamStage(PipelineStage.STRUCTURE.name()));
        assertEquals(PipelineStage.STRUCTURE.name(),
                StageFunnelRules.resolveUpstreamStage(PipelineStage.PREPROCESS.name()));
        assertEquals(PipelineStage.PREPROCESS.name(),
                StageFunnelRules.resolveUpstreamStage(PipelineStage.CHUNK.name()));
        assertEquals(PipelineStage.CHUNK.name(),
                StageFunnelRules.resolveUpstreamStage(PipelineStage.EMBED.name()));
    }

    /**
     * 未知环节按 40001 拒绝。
     *
     * <p>守护 {@code resolveUpstreamStage} 的白名单：未知环节被放行会静默查错数据。
     */
    @Test
    void resolveUpstreamStageShouldRejectUnknownStage() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> StageFunnelRules.resolveUpstreamStage("BUILD_INDEX"));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }
}
