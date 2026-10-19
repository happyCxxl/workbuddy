package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.RetrievalRuleResolver;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDTO;
import com.workbuddy.common.dto.request.strategy.StrategyVersionUpdateDTO;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.retrieval.RetrievalRuleSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略版本服务单测：类型白名单 / 列表（启用中 vs 全部）/ 创建 / 编辑=复制新版本 / 启停 /
 * 删除（有绑定引用禁删）/ CHUNK 与 PREPROCESS 配置校验。
 */
@ExtendWith(MockitoExtension.class)
class StrategyVersionServiceImplTest {

    @Mock
    private WbPipelineStrategyVersionDbService strategyVersionDbService;
    @Mock
    private WbStrategyBindingDbService strategyBindingDbService;
    @Mock
    private RetrievalRuleResolver retrievalRuleResolver;

    private StrategyVersionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StrategyVersionServiceImpl(strategyVersionDbService, strategyBindingDbService,
                retrievalRuleResolver);
    }

    private WbPipelineStrategyVersion row(Long id, String version, String status) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(id);
        row.setType("CHUNK");
        row.setName("chunk-hybrid");
        row.setVersion(version);
        row.setConfigSnapshot("{\"routes\":{}}");
        row.setCreateTime(java.time.LocalDateTime.of(2026, 10, 9, 10, 0));
        row.setStatus(status);
        return row;
    }

    private StrategyVersionCreateDTO createDto() {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("CHUNK");
        dto.setName("chunk-hybrid");
        dto.setVersion("v2");
        dto.setConfigSnapshot("{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\"}}}");
        return dto;
    }

    private StrategyVersionUpdateDTO updateDto() {
        StrategyVersionUpdateDTO dto = new StrategyVersionUpdateDTO();
        dto.setName("chunk-strict");
        dto.setVersion("v2");
        dto.setConfigSnapshot("{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\"}}}");
        return dto;
    }

    /** CHUNK 策略创建 DTO：routes/pipeline 配置结构 */
    private StrategyVersionCreateDTO chunkDto(String configSnapshot) {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("CHUNK");
        dto.setName("chunk-hybrid");
        dto.setVersion("v1");
        dto.setConfigSnapshot(configSnapshot);
        return dto;
    }

    /**
     * 校验点：{@code listEnabledShouldReturnMappedVersions}。
     */
    @Test
    void listEnabledShouldReturnMappedVersions() {
        when(strategyVersionDbService.listEnabledByType("CHUNK"))
                .thenReturn(List.of(row(9L, "v2", "ACTIVE")));

        List<StrategyVersionVO> vos = service.list("CHUNK", false);

        assertEquals(java.time.LocalDateTime.of(2026, 10, 9, 10, 0), vos.getFirst().getCreateTime());

        assertEquals(1, vos.size());
        assertEquals(9L, vos.getFirst().getId());
        assertEquals("ACTIVE", vos.getFirst().getStatus());
        verify(strategyVersionDbService, never()).listByType(any());
    }

    /**
     * 校验点：{@code listWithIncludeInactiveShouldReturnAll}。
     */
    @Test
    void listWithIncludeInactiveShouldReturnAll() {
        when(strategyVersionDbService.listByType("CHUNK"))
                .thenReturn(List.of(row(10L, "v1", "INACTIVE")));

        List<StrategyVersionVO> vos = service.list("CHUNK", true);

        assertEquals(1, vos.size());
        assertEquals("INACTIVE", vos.getFirst().getStatus());
        verify(strategyVersionDbService, never()).listEnabledByType(any());
    }

    /**
     * 校验点：{@code listWithUnknownTypeShouldReject}。
     */
    @Test
    void listWithUnknownTypeShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.list("INDEX", false));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyVersionDbService, never()).listEnabledByType(any());
    }

    /**
     * 校验点：{@code listWithBlankTypeShouldReject}。
     */
    @Test
    void listWithBlankTypeShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.list("  ", false));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyVersionDbService, never()).listEnabledByType(any());
    }

    /**
     * 校验点：{@code createShouldSaveAsActive}。
     */
    @Test
    void createShouldSaveAsActive() {
        when(strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(11L);
            return true;
        });

        StrategyVersionVO vo = service.create(createDto());

        assertEquals(11L, vo.getId());
        assertEquals("ACTIVE", vo.getStatus());
    }

    /**
     * 校验点：{@code createWithUnknownTypeShouldReject}。
     */
    @Test
    void createWithUnknownTypeShouldReject() {
        StrategyVersionCreateDTO dto = chunkDto("{\"routes\":{}}");
        dto.setType("INDEX");
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.create(dto));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createDuplicateKeyShouldReject}。
     */
    @Test
    void createDuplicateKeyShouldReject() {
        when(strategyVersionDbService.save(any(WbPipelineStrategyVersion.class)))
                .thenThrow(new DuplicateKeyException("uk_type_name_version"));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.create(createDto()));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("同环节同名版本已存在", e.getMessage());
    }

    /**
     * 校验点：{@code updateShouldCopyAsNewVersion}。
     */
    @Test
    void updateShouldCopyAsNewVersion() {
        // 策略版本行不可变：编辑 = 复制新行（旧行原样保留，新行按 dto 注册且默认 ACTIVE）
        WbPipelineStrategyVersion existing = row(9L, "v1", "INACTIVE");
        when(strategyVersionDbService.getById(9L)).thenReturn(existing);

        StrategyVersionVO vo = service.update(9L, updateDto());

        assertEquals("chunk-strict", vo.getName());
        assertEquals("v2", vo.getVersion());
        assertEquals("{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\"}}}", vo.getConfigSnapshot());
        assertEquals("ACTIVE", vo.getStatus());
        ArgumentCaptor<WbPipelineStrategyVersion> captor = ArgumentCaptor.forClass(WbPipelineStrategyVersion.class);
        verify(strategyVersionDbService).save(captor.capture());
        assertEquals("CHUNK", captor.getValue().getType());
        // 旧行未被改动
        verify(strategyVersionDbService, never()).updateById(any(WbPipelineStrategyVersion.class));
        assertEquals("chunk-hybrid", existing.getName());
        assertEquals("v1", existing.getVersion());
        assertEquals("INACTIVE", existing.getStatus());
    }

    /**
     * 校验点：{@code updateNotFoundShouldReject}。
     */
    @Test
    void updateNotFoundShouldReject() {
        when(strategyVersionDbService.getById(9L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.update(9L, updateDto()));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(strategyVersionDbService, never()).save(any());
        verify(strategyVersionDbService, never()).updateById(any());
    }

    /**
     * 校验点：{@code enableShouldSetActive}。
     */
    @Test
    void enableShouldSetActive() {
        when(strategyVersionDbService.getById(9L))
                .thenReturn(row(9L, "v1", "INACTIVE"));

        StrategyVersionVO vo = service.enable(9L);

        assertEquals("ACTIVE", vo.getStatus());
        verify(strategyVersionDbService).updateById(any(WbPipelineStrategyVersion.class));
    }

    /**
     * 校验点：{@code disableShouldSetInactive}。
     */
    @Test
    void disableShouldSetInactive() {
        when(strategyVersionDbService.getById(9L))
                .thenReturn(row(9L, "v1", "ACTIVE"));

        StrategyVersionVO vo = service.disable(9L);

        assertEquals("INACTIVE", vo.getStatus());
        verify(strategyVersionDbService).updateById(any(WbPipelineStrategyVersion.class));
    }

    /**
     * 校验点：{@code enableNotFoundShouldReject}。
     */
    @Test
    void enableNotFoundShouldReject() {
        when(strategyVersionDbService.getById(9L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.enable(9L));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(strategyVersionDbService, never()).updateById(any());
    }

    /**
     * 校验点：{@code deleteShouldRemoveWhenNoBindingReference}。
     */
    @Test
    void deleteShouldRemoveWhenNoBindingReference() {
        when(strategyVersionDbService.getById(9L))
                .thenReturn(row(9L, "v1", "ACTIVE"));
        when(strategyBindingDbService.existsByStrategyVersionId(9L)).thenReturn(false);
        when(strategyVersionDbService.removeById(9L)).thenReturn(true);

        assertTrue(service.delete(9L));
        verify(strategyVersionDbService).removeById(9L);

        // 受影响 0 行（并发下已被别人删掉）不能报成功
        when(strategyVersionDbService.removeById(9L)).thenReturn(false);
        assertFalse(service.delete(9L));
    }

    /**
     * 校验点：{@code deleteShouldRejectWhenBoundByKnowledgeBase}。
     */
    @Test
    void deleteShouldRejectWhenBoundByKnowledgeBase() {
        // 策略版本行不可变：有绑定引用禁物理删除（停用代替删除）
        when(strategyVersionDbService.getById(9L))
                .thenReturn(row(9L, "v1", "ACTIVE"));
        when(strategyBindingDbService.existsByStrategyVersionId(9L)).thenReturn(true);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.delete(9L));
        assertEquals(ErrorCode.STRATEGY_BOUND_DELETE_FORBIDDEN, e.getErrorCode());
        verify(strategyVersionDbService, never()).removeById(any(Serializable.class));
    }

    /**
     * 校验点：{@code deleteNotFoundShouldReject}。
     */
    @Test
    void deleteNotFoundShouldReject() {
        when(strategyVersionDbService.getById(9L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.delete(9L));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(strategyVersionDbService, never()).removeById(any(Serializable.class));
    }

    // ---------------- CHUNK 配置校验（结构 / 支持集 / 参数范围 / 不变量 / 互斥） ----------------

    /**
     * 校验点：{@code createChunkWithValidConfigShouldSave}。
     */
    @Test
    void createChunkWithValidConfigShouldSave() {
        when(strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(12L);
            return true;
        });

        StrategyVersionVO vo = service.create(chunkDto(
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
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(chunkDto("{not-json")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("配置不是合法 JSON", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createChunkWithUnsupportedAlgorithmShouldReject}。
     */
    @Test
    void createChunkWithUnsupportedAlgorithmShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(chunkDto("{\"routes\":{\"body\":{\"algorithm\":\"semantic\"}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("算法尚未支持: body.semantic", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createChunkWithParamOutOfRangeShouldReject}。
     */
    @Test
    void createChunkWithParamOutOfRangeShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(chunkDto(
                        "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                                + "\"params\":{\"targetMaxLen\":\"99999\"}}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("参数越界: body.targetMaxLen（允许 100~5000）", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createChunkWithSoftBelowTargetShouldReject}。
     */
    @Test
    void createChunkWithSoftBelowTargetShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(chunkDto(
                        "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                                + "\"params\":{\"targetMaxLen\":\"800\",\"softMaxLen\":\"600\"}}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("软上限必须 ≥ 目标片长上限（softMaxLen ≥ targetMaxLen）", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createChunkWithTableInBodyFlowAndContextMergedShouldReject}。
     */
    @Test
    void createChunkWithTableInBodyFlowAndContextMergedShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(chunkDto(
                        "{\"routes\":{\"table\":{\"algorithm\":\"context-merged\"}},"
                                + "\"pipeline\":{\"tableInBodyFlow\":\"ON\"}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("表格并入正文流与表+引导段落不可同时启用", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    // ---------------- PREPROCESS 配置校验（结构/枚举/参数范围/自定义正则） ----------------

    private StrategyVersionCreateDTO preprocDto(String configSnapshot) {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("PREPROCESS");
        dto.setName("preproc-strict");
        dto.setVersion("v2");
        dto.setConfigSnapshot(configSnapshot);
        return dto;
    }

    /**
     * 校验点：{@code createPreprocWithValidConfigShouldSave}。
     */
    @Test
    void createPreprocWithValidConfigShouldSave() {
        when(strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(13L);
            return true;
        });

        StrategyVersionVO vo = service.create(preprocDto(
                "{\"rules\":{\"headerFooter\":{\"action\":\"EXCLUDE\"},"
                        + "\"toc\":{\"action\":\"MARK\",\"params\":{\"minLinesPerPage\":\"5\"}}},"
                        + "\"custom\":{\"enabled\":\"ON\",\"rules\":[{\"pattern\":\"版权所有\\\\s*©.*\",\"action\":\"REMOVE\"}]}}"));

        assertEquals(13L, vo.getId());
        assertEquals("preproc-strict", vo.getName());
    }

    /**
     * 校验点：{@code createPreprocWithIllegalActionShouldReject}。
     */
    @Test
    void createPreprocWithIllegalActionShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(preprocDto("{\"rules\":{\"headerFooter\":{\"action\":\"DELETE\"}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("处置方式非法: headerFooter.DELETE", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createPreprocWithParamOutOfRangeShouldReject}。
     */
    @Test
    void createPreprocWithParamOutOfRangeShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(preprocDto(
                        "{\"rules\":{\"toc\":{\"params\":{\"minLinesPerPage\":\"999\"}}}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("参数越界: toc.minLinesPerPage（允许 1~50）", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createPreprocWithInvalidRegexShouldReject}。
     */
    @Test
    void createPreprocWithInvalidRegexShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(preprocDto(
                        "{\"custom\":{\"rules\":[{\"pattern\":\"(\",\"action\":\"REMOVE\"}]}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("自定义规则存在不合法的正则表达式: (", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createPreprocWithReplaceMissingReplacementShouldReject}。
     */
    @Test
    void createPreprocWithReplaceMissingReplacementShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(preprocDto(
                        "{\"custom\":{\"rules\":[{\"pattern\":\"电话.*\",\"action\":\"REPLACE\"}]}}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("替换动作需填写替换文本", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    // ---------------- EMBED 配置校验（模型目录/模板占位符/数值范围/枚举 + 保存时目录冗余进快照） ----------------

    private StrategyVersionCreateDTO embedDto(String configSnapshot) {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("EMBED");
        dto.setName("embed-default");
        dto.setVersion("v1");
        dto.setConfigSnapshot(configSnapshot);
        return dto;
    }

    /**
     * 校验点：{@code createEmbedWithValidConfigShouldSaveSnapshotAsIs}。
     */
    @Test
    void createEmbedWithValidConfigShouldSaveSnapshotAsIs() {
        ArgumentCaptor<WbPipelineStrategyVersion> captor = ArgumentCaptor.forClass(WbPipelineStrategyVersion.class);
        when(strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(14L);
            return true;
        });

        StrategyVersionVO vo = service.create(embedDto(
                "{\"model\":\"text-embedding-v4\",\"docTemplate\":\"{content}\",\"queryTemplate\":\"{query}\","
                        + "\"batchSize\":64,\"timeoutMs\":30000,\"maxRetries\":2,"
                        + "\"cacheEnabled\":\"ON\",\"includeParent\":\"OFF\",\"skipEmpty\":\"ON\"}"));

        assertEquals(14L, vo.getId());
        verify(strategyVersionDbService).save(captor.capture());
        java.util.Map<String, Object> snapshot = JsonUtil.toMap(captor.getValue().getConfigSnapshot());
        assertEquals("text-embedding-v4", snapshot.get("model"));
        assertEquals(64, snapshot.get("batchSize"));
        assertNull(snapshot.get("dimension"), "快照原样落库，不再回填目录冗余");
    }

    /** 目录校验已移除：未收录的模型名原样保存，存在性交给调用期判定（{@code createEmbedWithUncatalogedModelShouldSaveAsIs}）。 */
    @Test
    void createEmbedWithUncatalogedModelShouldSaveAsIs() {
        when(strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenReturn(true);

        service.create(embedDto("{\"model\":\"no-such-model\",\"docTemplate\":\"{content}\",\"queryTemplate\":\"{query}\"}"));

        verify(strategyVersionDbService).save(any(WbPipelineStrategyVersion.class));
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(strategyVersionDbService).getInvocations().stream()
                .anyMatch(inv -> "save".equals(inv.getMethod().getName())));
    }

    /**
     * 校验点：{@code createEmbedWithTemplateMissingPlaceholderShouldReject}。
     */
    @Test
    void createEmbedWithTemplateMissingPlaceholderShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(embedDto("{\"model\":\"text-embedding-v4\",\"docTemplate\":\"{titlePath}\"}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("docTemplate 必须包含 {content} 占位符", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createEmbedWithRangeViolationShouldReject}。
     */
    @Test
    void createEmbedWithRangeViolationShouldReject() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(embedDto("{\"model\":\"text-embedding-v4\",\"batchSize\":999}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("batchSize 越界（允许 1~128）", e.getMessage());
        verify(strategyVersionDbService, never()).save(any());
    }

    // ---------------- RETRIEVAL 配置校验（规则解析器收口：白名单/边界/预留能力锁定） ----------------

    private StrategyVersionCreateDTO retrievalDto(String configSnapshot) {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("RETRIEVAL");
        dto.setName("hybrid-rrf-k60-top10");
        dto.setVersion("v1");
        dto.setConfigSnapshot(configSnapshot);
        return dto;
    }

    /**
     * 校验点：{@code createRetrievalWithValidConfigShouldSave}。
     */
    @Test
    void createRetrievalWithValidConfigShouldSave() {
        when(retrievalRuleResolver.validate(anyString())).thenReturn(new RetrievalRuleSpec());
        when(strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(15L);
            return true;
        });

        StrategyVersionVO vo = service.create(retrievalDto("{\"channel\":\"HYBRID\"}"));

        assertEquals(15L, vo.getId());
        assertEquals("RETRIEVAL", vo.getType());
        verify(retrievalRuleResolver).validate("{\"channel\":\"HYBRID\"}");
    }

    /**
     * 校验点：{@code createRetrievalWithUnknownFieldShouldReject}。
     */
    @Test
    void createRetrievalWithUnknownFieldShouldReject() {
        when(retrievalRuleResolver.validate(anyString()))
                .thenThrow(new KnowledgeException(ErrorCode.PARAM_INVALID, "未知检索规则字段: unknown"));

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(retrievalDto("{\"channel\":\"HYBRID\",\"unknown\":1}")));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createRetrievalWithLockedCapabilityShouldReject}。
     */
    @Test
    void createRetrievalWithLockedCapabilityShouldReject() {
        when(retrievalRuleResolver.validate(anyString()))
                .thenThrow(new KnowledgeException(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, "该检索能力尚未启用: 加权融合"));

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.create(retrievalDto("{\"fusion\":{\"mode\":\"WEIGHTED\"}}")));

        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, e.getErrorCode());
        verify(strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code listRetrievalTypeShouldBeSupported}。
     */
    @Test
    void listRetrievalTypeShouldBeSupported() {
        when(strategyVersionDbService.listEnabledByType("RETRIEVAL")).thenReturn(List.of());

        service.list("RETRIEVAL", false);

        verify(strategyVersionDbService).listEnabledByType("RETRIEVAL");
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(strategyVersionDbService).getInvocations().stream()
                .anyMatch(inv -> "listEnabledByType".equals(inv.getMethod().getName())));
    }

    /**
     * 空配置快照：按 40001 拒绝并给出"配置不能为空"而不是"不是合法 JSON"。
     *
     * <p>守护 {@code validateConfig} 的空白校验：漏校验会落到 JSON 解析分支，文案误导。
     */
    @Test
    void createWithBlankConfigShouldSayConfigRequired() {
        StrategyVersionCreateDTO dto = preprocDto(" ");

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.create(dto));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("配置不能为空"), e.getMessage());
    }
}
