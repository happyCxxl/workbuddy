package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDTO;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.retrieval.RetrievalRuleSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略版本配置校验用例：CHUNK（结构 / 支持集 / 参数范围 / 不变量 / 互斥）/
 * PREPROCESS（枚举 / 参数范围 / 自定义正则）/ EMBED（模板占位符 / 数值范围）/
 * RETRIEVAL（规则解析器收口：白名单 / 预留能力锁定）。
 *
 * <p>增删改查用例在 {@link StrategyVersionCrudTest}；装配与夹具数据见 {@link StrategyVersionTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class StrategyVersionValidationTest {

    private StrategyVersionTestFixture fixture;

    private StrategyVersionServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new StrategyVersionTestFixture();
        service = fixture.service();
    }

    /**
     * 校验点：{@code createChunkWithValidConfigShouldSave}。
     */
    @Test
    void createChunkWithValidConfigShouldSave() {
        when(fixture.strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(12L);
            return true;
        });

        StrategyVersionVO vo = service.create(fixture.chunkDto(
                "{\"routes\":{\"body\":{\"algorithm\":\"structure-hybrid\","
                        + "\"params\":{\"targetMaxLen\":\"800\",\"softMaxLen\":\"1600\"}}},"
                        + "\"pipeline\":{\"titleInContent\":\"ON\",\"minMergeLen\":\"300\"}}"));

        assertEquals(12L, vo.getId());
        assertEquals("chunk-hybrid", vo.getName());
    }

    /**
     * 校验点：{@code createChunkWithInvalidJsonShouldReject}。
     */
    @Test
    void createChunkWithInvalidJsonShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.chunkDto("{not-json")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("配置不是合法 JSON", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createChunkWithUnsupportedAlgorithmShouldReject}。
     */
    @Test
    void createChunkWithUnsupportedAlgorithmShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.chunkDto(
                        "{\"routes\":{\"body\":{\"algorithm\":\"semantic\"}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("算法尚未支持: body.semantic", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createChunkWithParamOutOfRangeShouldReject}。
     */
    @Test
    void createChunkWithParamOutOfRangeShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.chunkDto(
                        "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                                + "\"params\":{\"targetMaxLen\":\"99999\"}}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("参数越界: body.targetMaxLen（允许 100~5000）", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createChunkWithSoftBelowTargetShouldReject}。
     */
    @Test
    void createChunkWithSoftBelowTargetShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.chunkDto(
                        "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                                + "\"params\":{\"targetMaxLen\":\"800\",\"softMaxLen\":\"600\"}}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("软上限必须 ≥ 目标片长上限（softMaxLen ≥ targetMaxLen）", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createChunkWithTableInBodyFlowAndContextMergedShouldReject}。
     */
    @Test
    void createChunkWithTableInBodyFlowAndContextMergedShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.chunkDto(
                        "{\"routes\":{\"table\":{\"algorithm\":\"context-merged\"}},"
                                + "\"pipeline\":{\"tableInBodyFlow\":\"ON\"}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("表格并入正文流与表+引导段落不可同时启用", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createPreprocWithValidConfigShouldSave}。
     */
    @Test
    void createPreprocWithValidConfigShouldSave() {
        when(fixture.strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(13L);
            return true;
        });

        StrategyVersionVO vo = service.create(fixture.preprocDto(
                "{\"rules\":{\"headerFooter\":{\"action\":\"EXCLUDE\"},"
                        + "\"toc\":{\"action\":\"MARK\",\"params\":{\"minLinesPerPage\":\"5\"}}},"
                        + "\"custom\":{\"enabled\":\"ON\","
                        + "\"rules\":[{\"pattern\":\"版权所有\\\\s*©.*\",\"action\":\"REMOVE\"}]}}"));

        assertEquals(13L, vo.getId());
        assertEquals("preproc-strict", vo.getName());
    }

    /**
     * 校验点：{@code createPreprocWithIllegalActionShouldReject}。
     */
    @Test
    void createPreprocWithIllegalActionShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.preprocDto("{\"rules\":{\"headerFooter\":{\"action\":\"DELETE\"}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("处置方式非法: headerFooter.DELETE", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createPreprocWithParamOutOfRangeShouldReject}。
     */
    @Test
    void createPreprocWithParamOutOfRangeShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.preprocDto(
                        "{\"rules\":{\"toc\":{\"params\":{\"minLinesPerPage\":\"999\"}}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("参数越界: toc.minLinesPerPage（允许 1~50）", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createPreprocWithInvalidRegexShouldReject}。
     */
    @Test
    void createPreprocWithInvalidRegexShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.preprocDto(
                        "{\"custom\":{\"rules\":[{\"pattern\":\"(\",\"action\":\"REMOVE\"}]}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("自定义规则存在不合法的正则表达式: (", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createPreprocWithReplaceMissingReplacementShouldReject}。
     */
    @Test
    void createPreprocWithReplaceMissingReplacementShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.preprocDto(
                        "{\"custom\":{\"rules\":[{\"pattern\":\"电话.*\",\"action\":\"REPLACE\"}]}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("替换动作需填写替换文本", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createEmbedWithValidConfigShouldSaveSnapshotAsIs}。
     */
    @Test
    void createEmbedWithValidConfigShouldSaveSnapshotAsIs() {
        ArgumentCaptor<WbPipelineStrategyVersion> captor = ArgumentCaptor.forClass(WbPipelineStrategyVersion.class);
        when(fixture.strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(14L);
            return true;
        });

        StrategyVersionVO vo = service.create(fixture.embedDto(
                "{\"model\":\"text-embedding-v4\",\"docTemplate\":\"{content}\",\"queryTemplate\":\"{query}\","
                        + "\"batchSize\":64,\"timeoutMs\":30000,\"maxRetries\":2,"
                        + "\"cacheEnabled\":\"ON\",\"includeParent\":\"OFF\",\"skipEmpty\":\"ON\"}"));

        assertEquals(14L, vo.getId());
        verify(fixture.strategyVersionDbService).save(captor.capture());
        java.util.Map<String, Object> snapshot = JsonUtil.toMap(captor.getValue().getConfigSnapshot());
        assertEquals("text-embedding-v4", snapshot.get("model"));
        assertEquals(64, snapshot.get("batchSize"));
        assertNull(snapshot.get("dimension"), "快照原样落库，不再回填目录冗余");
    }

    /** 目录校验已移除：未收录的模型名原样保存，存在性交给调用期判定（{@code createEmbedWithUncatalogedModelShouldSaveAsIs}）。 */
    @Test
    void createEmbedWithUncatalogedModelShouldSaveAsIs() {
        when(fixture.strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenReturn(true);

        service.create(fixture.embedDto(
                "{\"model\":\"no-such-model\",\"docTemplate\":\"{content}\",\"queryTemplate\":\"{query}\"}"));

        verify(fixture.strategyVersionDbService).save(any(WbPipelineStrategyVersion.class));
        assertTrue(mockingDetails(fixture.strategyVersionDbService).getInvocations().stream()
                .anyMatch(inv -> "save".equals(inv.getMethod().getName())));
    }

    /**
     * 校验点：{@code createEmbedWithTemplateMissingPlaceholderShouldReject}。
     */
    @Test
    void createEmbedWithTemplateMissingPlaceholderShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.embedDto(
                        "{\"model\":\"text-embedding-v4\",\"docTemplate\":\"{titlePath}\"}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("docTemplate 必须包含 {content} 占位符", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createEmbedWithRangeViolationShouldReject}。
     */
    @Test
    void createEmbedWithRangeViolationShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.embedDto("{\"model\":\"text-embedding-v4\",\"batchSize\":999}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("batchSize 越界（允许 1~128）", e.getMessage());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createRetrievalWithValidConfigShouldSave}。
     */
    @Test
    void createRetrievalWithValidConfigShouldSave() {
        when(fixture.retrievalRuleResolver.validate(anyString())).thenReturn(new RetrievalRuleSpec());
        when(fixture.strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(15L);
            return true;
        });

        StrategyVersionVO vo = service.create(fixture.retrievalDto("{\"channel\":\"HYBRID\"}"));

        assertEquals(15L, vo.getId());
        assertEquals("RETRIEVAL", vo.getType());
        verify(fixture.retrievalRuleResolver).validate("{\"channel\":\"HYBRID\"}");
    }

    /**
     * 校验点：{@code createRetrievalWithUnknownFieldShouldReject}。
     */
    @Test
    void createRetrievalWithUnknownFieldShouldReject() {
        when(fixture.retrievalRuleResolver.validate(anyString()))
                .thenThrow(new WorkbuddyException(ErrorCode.PARAM_INVALID, "未知检索规则字段: unknown"));

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.retrievalDto("{\"channel\":\"HYBRID\",\"unknown\":1}")));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createRetrievalWithLockedCapabilityShouldReject}。
     */
    @Test
    void createRetrievalWithLockedCapabilityShouldReject() {
        when(fixture.retrievalRuleResolver.validate(anyString()))
                .thenThrow(new WorkbuddyException(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, "该检索能力尚未启用: 加权融合"));

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.create(fixture.retrievalDto("{\"fusion\":{\"mode\":\"WEIGHTED\"}}")));

        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, e.getErrorCode());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 空配置快照：按 40001 拒绝并给出"配置不能为空"而不是"不是合法 JSON"。
     *
     * <p>守护 {@code validateConfig} 的空白校验：漏校验会落到 JSON 解析分支，文案误导。
     */
    @Test
    void createWithBlankConfigShouldSayConfigRequired() {
        StrategyVersionCreateDTO dto = fixture.preprocDto(" ");

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.create(dto));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("配置不能为空"), e.getMessage());
    }
}
