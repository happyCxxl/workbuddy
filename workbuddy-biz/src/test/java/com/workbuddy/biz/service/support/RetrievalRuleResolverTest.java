package com.workbuddy.biz.service.support;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.worker.retrieval.RetrievalCapability;
import com.workbuddy.worker.retrieval.RetrievalRuleSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 检索规则解析/校验器单测：
 * 全目录 body 解析 / 未知字段拒绝 / 取值白名单 / 参数边界 / 预留能力锁定（40451）/ 能力开启后放行。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class RetrievalRuleResolverTest {

    private static final String VALID_BODY = "{\"channel\":\"HYBRID\","
            + "\"fusion\":{\"mode\":\"RRF\",\"rrfK\":60,\"perChannelLimit\":50},"
            + "\"preprocess\":{\"mode\":\"NONE\"},\"rerank\":{\"mode\":\"NONE\"},"
            + "\"postprocess\":{\"mode\":\"PARENT_EXPAND\"},\"topK\":10,\"scoreThreshold\":0}";

    @Mock
    private RetrievalCapabilityRegistry capabilityRegistry;

    private RetrievalRuleResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RetrievalRuleResolver(capabilityRegistry);
    }

    @Test
    void validateShouldPassFullDirectoryBody() {
        RetrievalRuleSpec spec = resolver.validate(VALID_BODY);

        assertEquals("HYBRID", spec.getChannel());
        assertEquals("RRF", spec.getFusion().getMode());
        assertEquals(60, spec.getFusion().getRrfK());
        assertEquals("PARENT_EXPAND", spec.getPostprocess().getMode());
        assertEquals(10, spec.getTopK());
        assertEquals(0, spec.getScoreThreshold(), 0.001);
    }

    @Test
    void parseShouldApplyDefaultsForMissingFields() {
        RetrievalRuleSpec spec = resolver.parse("{\"channel\":\"FULLTEXT\"}");

        assertEquals("FULLTEXT", spec.getChannel());
        assertEquals("RRF", spec.getFusion().getMode());
        assertEquals("NONE", spec.getPostprocess().getMode());
        assertEquals(10, spec.getTopK());
    }

    @Test
    void validateShouldRejectUnknownField() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"channel\":\"HYBRID\",\"unknown\":1}"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("未知检索规则字段"), e.getMessage());
    }

    @Test
    void validateShouldRejectUnknownNestedField() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"fusion\":{\"mode\":\"RRF\",\"weight\":0.5}}"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("未知fusion字段"), e.getMessage());
    }

    @Test
    void validateShouldRejectIllegalChannel() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"channel\":\"GRAPH\"}"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("非法检索通道"), e.getMessage());
    }

    @Test
    void validateShouldRejectTopKOutOfRange() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"topK\":0}"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    @Test
    void validateShouldRejectBlankAndInvalidJson() {
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(KnowledgeException.class,
                () -> resolver.validate("")).getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(KnowledgeException.class,
                () -> resolver.validate("not-json")).getErrorCode());
    }

    @Test
    void validateShouldLockWeightedFusionWhenCapabilityOff() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"fusion\":{\"mode\":\"WEIGHTED\"}}"));
        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, e.getErrorCode());
        assertTrue(e.getMessage().contains("加权融合"), e.getMessage());
    }

    @Test
    void validateShouldLockReservedModesWhenCapabilityOff() {
        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, assertThrows(KnowledgeException.class,
                () -> resolver.validate("{\"preprocess\":{\"mode\":\"REWRITE\"}}")).getErrorCode());
        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, assertThrows(KnowledgeException.class,
                () -> resolver.validate("{\"rerank\":{\"mode\":\"RERANK\"}}")).getErrorCode());
        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, assertThrows(KnowledgeException.class,
                () -> resolver.validate("{\"postprocess\":{\"mode\":\"NEIGHBOR_EXPAND\"}}")).getErrorCode());
        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, assertThrows(KnowledgeException.class,
                () -> resolver.validate("{\"scoreThreshold\":0.8}")).getErrorCode());
    }

    @Test
    void validateShouldAllowLockedFeatureWhenCapabilityOn() {
        when(capabilityRegistry.isEnabled(RetrievalCapability.WEIGHTED)).thenReturn(true);

        RetrievalRuleSpec spec = resolver.validate("{\"fusion\":{\"mode\":\"WEIGHTED\"}}");

        assertEquals("WEIGHTED", spec.getFusion().getMode());
    }
}
