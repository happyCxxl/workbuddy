package com.workbuddy.biz.service.support;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.worker.retrieval.RetrievalCapability;
import com.workbuddy.worker.retrieval.RetrievalRuleSpec;
import com.workbuddy.biz.service.support.retrieval.RetrievalRuleResolver;
import com.workbuddy.biz.service.support.retrieval.RetrievalCapabilityRegistry;
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

    /**
     * 注册校验：全字段合法快照原样解析。
     *
     * <p>守护 {@code validate}：合法配置被拒即失败。
     */
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

    /**
     * 解析：缺省字段按契约默认值补全。
     *
     * <p>守护 {@code parse}：默认值错即失败。
     */
    @Test
    void parseShouldApplyDefaultsForMissingFields() {
        RetrievalRuleSpec spec = resolver.parse("{\"channel\":\"FULLTEXT\"}");

        assertEquals("FULLTEXT", spec.getChannel());
        assertEquals("RRF", spec.getFusion().getMode());
        assertEquals("NONE", spec.getPostprocess().getMode());
        assertEquals(10, spec.getTopK());
    }

    /**
     * 注册校验：顶层未知字段按 40001 拒绝。
     *
     * <p>守护 {@code validate}：未知字段混进快照即失败。
     */
    @Test
    void validateShouldRejectUnknownField() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"channel\":\"HYBRID\",\"unknown\":1}"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("未知检索规则字段"), e.getMessage());
    }

    /**
     * 注册校验：嵌套对象未知字段按 40001 拒绝。
     *
     * <p>守护 {@code validate}：未知字段混进快照即失败。
     */
    @Test
    void validateShouldRejectUnknownNestedField() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"fusion\":{\"mode\":\"RRF\",\"weight\":0.5}}"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("未知fusion字段"), e.getMessage());
    }

    /**
     * 注册校验：未登记检索通道按 40001 拒绝。
     *
     * <p>守护 {@code validate}：非法通道被执行即失败。
     */
    @Test
    void validateShouldRejectIllegalChannel() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"channel\":\"GRAPH\"}"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("非法检索通道"), e.getMessage());
    }

    /**
     * 注册校验：topK 越界按 40001 拒绝。
     *
     * <p>守护 {@code validate}：越界取值落库即失败。
     */
    @Test
    void validateShouldRejectTopKOutOfRange() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"topK\":0}"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 解析：空串与非法 JSON 都按 40001 拒绝。
     *
     * <p>守护 {@code validate}：非法 JSON 抛系统异常即失败。
     */
    @Test
    void validateShouldRejectBlankAndInvalidJson() {
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(KnowledgeException.class,
                () -> resolver.validate("")).getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(KnowledgeException.class,
                () -> resolver.validate("not-json")).getErrorCode());
    }

    /**
     * 预留能力：加权融合未启用时按 40451 拒绝。
     *
     * <p>守护 {@code validate}：未启用能力可注册即失败。
     */
    @Test
    void validateShouldLockWeightedFusionWhenCapabilityOff() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(
                "{\"fusion\":{\"mode\":\"WEIGHTED\"}}"));
        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, e.getErrorCode());
        assertTrue(e.getMessage().contains("加权融合"), e.getMessage());
    }

    /**
     * 预留能力：改写/重排/相邻扩展/阈值截断未启用时均拒绝。
     *
     * <p>守护 {@code validate}：漏一道能力门即失败。
     */
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

    /**
     * 预留能力开启后放行对应模式。
     *
     * <p>守护 {@code validate}：能力开启仍被拦即失败。
     */
    @Test
    void validateShouldAllowLockedFeatureWhenCapabilityOn() {
        when(capabilityRegistry.isEnabled(RetrievalCapability.WEIGHTED)).thenReturn(true);

        RetrievalRuleSpec spec = resolver.validate("{\"fusion\":{\"mode\":\"WEIGHTED\"}}");

        assertEquals("WEIGHTED", spec.getFusion().getMode());
    }

    /**
     * 每个嵌套配置对象都做字段白名单。
     *
     * <p>守护 {@code validate} 的四次 {@code checkNested}：漏查任一层都会让未知字段混进快照。
     */
    @Test
    void validateShouldRejectUnknownNestedFieldInEveryObject() {
        for (String body : new String[] {
                "{\"preprocess\":{\"mode\":\"NONE\",\"unknown\":1}}",
                "{\"rerank\":{\"mode\":\"NONE\",\"unknown\":1}}",
                "{\"postprocess\":{\"mode\":\"NONE\",\"unknown\":1}}"}) {
            KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(body), body);
            assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode(), body);
            assertTrue(e.getMessage().contains("未知"), e.getMessage());
        }
    }

    /**
     * 融合 / 预处理 / 重排 / 后处理四处的模式取值都在白名单内。
     *
     * <p>守护 {@code validate} 的四处取值白名单：放行未登记模式会按默认分支静默执行。
     */
    @Test
    void validateShouldRejectIllegalModes() {
        for (String body : new String[] {
                "{\"fusion\":{\"mode\":\"MAX\"}}",
                "{\"preprocess\":{\"mode\":\"MAGIC\"}}",
                "{\"rerank\":{\"mode\":\"MAGIC\"}}",
                "{\"postprocess\":{\"mode\":\"MAGIC\"}}"}) {
            KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(body), body);
            assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode(), body);
            assertTrue(e.getMessage().contains("非法"), e.getMessage());
        }
    }

    /**
     * 参数边界：取值域两端都放行、越界即拒绝。
     *
     * <p>守护 {@code validate} 的四处参数边界：写成开区间会挡掉合法配置。
     */
    @Test
    void validateShouldEnforceParameterBoundaries() {
        assertEquals(1, resolver.validate("{\"topK\":1}").getTopK());
        assertEquals(100, resolver.validate("{\"topK\":100}").getTopK());
        assertRejected("{\"topK\":101}");

        assertEquals(1, resolver.validate("{\"fusion\":{\"rrfK\":1}}").getFusion().getRrfK());
        assertRejected("{\"fusion\":{\"rrfK\":0}}");

        assertEquals(1, resolver.validate("{\"fusion\":{\"perChannelLimit\":1}}")
                .getFusion().getPerChannelLimit());
        assertEquals(1000, resolver.validate("{\"fusion\":{\"perChannelLimit\":1000}}")
                .getFusion().getPerChannelLimit());
        assertRejected("{\"fusion\":{\"perChannelLimit\":1001}}");
        assertRejected("{\"fusion\":{\"perChannelLimit\":0}}");

        assertEquals(0, resolver.validate("{\"scoreThreshold\":0}").getScoreThreshold(), 0.001);
        assertRejected("{\"scoreThreshold\":-1}");
    }

    /**
     * 查询扩展是预留能力：未启用时注册即按 40451 拒绝。
     *
     * <p>守护 {@code validate} 的 EXPAND 能力门：漏一道门会让未接通的扩展被执行。
     */
    @Test
    void validateShouldLockExpandCapability() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> resolver.validate("{\"preprocess\":{\"mode\":\"EXPAND\"}}"));

        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, e.getErrorCode());
        assertTrue(e.getMessage().contains("查询扩展"), e.getMessage());
    }

    /**
     * 断言按 40001 拒绝。
     *
     * @param body 规则快照
     */
    private void assertRejected(String body) {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> resolver.validate(body), body);
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode(), body);
    }

    /**
     * 嵌套字段不是对象：按 40001 拒绝并提示"必须为对象"。
     *
     * <p>守护 {@code checkNested} 的类型校验：漏校验会在强转处抛类型转换异常。
     */
    @Test
    void validateShouldRejectNestedNonObject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> resolver.validate("{\"fusion\":[]}"));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("必须为对象"), e.getMessage());
    }
}
