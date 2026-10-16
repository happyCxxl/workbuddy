package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.db.WbModelDbService;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.enums.model.AuthType;
import com.workbuddy.common.dto.request.model.ModelQueryDto;
import com.workbuddy.common.dto.request.model.ModelSaveDto;
import com.workbuddy.common.dto.request.model.ModelStatusDto;
import com.workbuddy.common.dto.response.model.ModelItemVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.audit.AuditEventPublisher;
import com.workbuddy.model.config.ModelVendorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 模型管理服务单测（数据访问打桩）：清单过滤与字段映射、新增与编辑的校验逐条、
 * 启停的定点回写与审计、厂商码必须在配置里的校验。
 *
 * @author cxxl
 */
class ModelServiceImplTest {

    /** 审计对象类型（与实现的取值一致） */
    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.MODEL.key();

    private WbModelDbService kbModelDbService;

    private AuditEventPublisher auditEventPublisher;

    private ModelVendorProperties vendorProperties;

    private ModelServiceImpl service;

    @BeforeEach
    void setUp() {
        kbModelDbService = mock(WbModelDbService.class);
        auditEventPublisher = mock(AuditEventPublisher.class);
        vendorProperties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        vendors.put("dashscope", vendor("sk-dashscope"));
        vendors.put("self-ocr", vendor(null));
        vendors.get("dashscope").setDisplayName("阿里百炼");
        vendorProperties.setVendors(vendors);
        service = new ModelServiceImpl(kbModelDbService, vendorProperties, auditEventPublisher);
    }

    @Test
    void pageShouldPassFiltersAndMapDirectoryFields() {
        when(kbModelDbService.pageByCondition(1, 10, "ENABLED", "qwen", "OCR"))
                .thenReturn(pageOf(row(2L, "dashscope", "qwen-vl-max", "OCR,EMBEDDING", "ENABLED")));

        IPage<ModelItemVO> result = service.page(query("OCR", "ENABLED", "  qwen  ", 1L, 10L));

        verify(kbModelDbService).pageByCondition(1, 10, "ENABLED", "qwen", "OCR");
        assertEquals(1, result.getRecords().size());
        assertEquals(1, result.getTotal());
        ModelItemVO item = result.getRecords().getFirst();
        assertEquals(2L, item.getId());
        assertEquals("dashscope", item.getVendorCode());
        assertEquals("阿里百炼", item.getVendorName());
        assertEquals("qwen-vl-max", item.getModelKey());
        assertEquals(List.of("OCR", "EMBEDDING"), item.getCapabilities());
        assertEquals("ENABLED", item.getStatus());
    }

    /** 页码与页长缺省按 1 / 10 兜底，筛选条件留空按不过滤下发 */
    @Test
    void pageShouldDefaultPaginationWhenAbsent() {
        when(kbModelDbService.pageByCondition(1, 10, null, null, null)).thenReturn(pageOf());

        IPage<ModelItemVO> result = service.page(query(null, null, "   ", null, null));

        assertTrue(result.getRecords().isEmpty());
        verify(kbModelDbService).pageByCondition(1, 10, null, null, null);
    }

    @Test
    void createShouldFillColumnsAndWriteAudit() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(false);
        ModelSaveDto dto = saveDto();

        ModelItemVO item = service.create(dto);

        WbModel saved = capturedSave();
        assertEquals("dashscope", saved.getVendorCode());
        assertEquals("qwen-max", saved.getModelKey());
        assertEquals("CHAT,EMBEDDING", saved.getCapabilities());
        assertEquals(1, saved.getThinkingEnabled());
        assertEquals(30720, saved.getInputLimit());
        assertEquals(8192, saved.getOutputLimit());
        assertEquals(32768, saved.getThinkingInputLimit());
        assertEquals(65536, saved.getThinkingOutputLimit());
        assertEquals(32768, saved.getContextWindow());
        assertEquals(new BigDecimal("20"), saved.getPriceInPerM());
        assertEquals(new BigDecimal("2"), saved.getPriceInPerMCacheHit());
        assertEquals(new BigDecimal("60"), saved.getPriceOutPerM());
        assertEquals("CNY", saved.getCurrency());
        assertEquals(600, saved.getRpmLimit());
        assertEquals(120000, saved.getTpmLimit());
        assertEquals("ENABLED", saved.getStatus());
        assertEquals(List.of("CHAT", "EMBEDDING"), item.getCapabilities());
        verify(auditEventPublisher).publish(eq(AuditActionType.MODEL_CREATE), eq(AUDIT_OBJECT_TYPE), isNull(),
                isNull(), anyString());
    }

    @Test
    void createShouldDefaultThinkingFlagToDisabled() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(false);
        ModelSaveDto dto = saveDto();
        dto.setThinkingEnabled(null);

        service.create(dto);

        assertEquals(0, capturedSave().getThinkingEnabled());
    }

    @Test
    void createShouldRejectVendorCodeAbsentFromConfig() {
        ModelSaveDto dto = saveDto();
        dto.setVendorCode("no-such-vendor");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.create(dto));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(error.getMessage().contains("workbuddy.model.vendors"), error.getMessage());
        assertTrue(error.getMessage().contains("no-such-vendor"), error.getMessage());
        verify(kbModelDbService, never()).save(any());
        verify(auditEventPublisher, never()).publish(any(), anyString(), any(), any(), any());
    }

    @Test
    void createShouldRejectDuplicateVendorAndModelKey() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(true);

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.create(saveDto()));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("唯一键"), error.getMessage());
        verify(kbModelDbService, never()).save(any());
    }

    @Test
    void createShouldRejectIllegalCapability() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(false);
        ModelSaveDto empty = saveDto();
        empty.setCapabilities(List.of());
        ModelSaveDto unknown = saveDto();
        unknown.setCapabilities(List.of("RERANK"));

        KnowledgeException emptyError = assertThrows(KnowledgeException.class, () -> service.create(empty));
        KnowledgeException unknownError = assertThrows(KnowledgeException.class, () -> service.create(unknown));

        assertEquals(ErrorCode.PARAM_INVALID, emptyError.getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID, unknownError.getErrorCode());
        assertTrue(unknownError.getMessage().contains("RERANK"), unknownError.getMessage());
        verify(kbModelDbService, never()).save(any());
    }

    @Test
    void createShouldAlwaysSaveEnabled() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(false);

        service.create(saveDto());

        assertEquals("ENABLED", capturedSave().getStatus());
    }

    @Test
    void updateShouldExcludeSelfAndOverwriteColumns() {
        when(kbModelDbService.getExistingById(5L))
                .thenReturn(row(5L, "dashscope", "qwen-max", "CHAT", "ENABLED"));
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", 5L)).thenReturn(false);
        ModelSaveDto dto = saveDto();
        dto.setPriceInPerM(null);

        ModelItemVO item = service.update(5L, dto);

        assertEquals(5L, item.getId());
        WbModel updated = capturedUpdate();
        assertEquals(5L, updated.getId());
        assertNull(updated.getPriceInPerM());
        verify(auditEventPublisher).publish(eq(AuditActionType.MODEL_UPDATE), eq(AUDIT_OBJECT_TYPE), eq(5L),
                anyString(), anyString());
    }

    /** 编辑不改状态：停用行编辑后保持停用（启停只走专用接口） */
    @Test
    void updateShouldKeepStatusWhenAbsent() {
        WbModel existing = row(7L, "dashscope", "qwen-max", "CHAT", "DISABLED");
        existing.setCurrency("USD");
        when(kbModelDbService.getExistingById(7L)).thenReturn(existing);
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", 7L)).thenReturn(false);
        ModelSaveDto dto = saveDto();
        dto.setCurrency(null);

        service.update(7L, dto);

        WbModel updated = capturedUpdate();
        assertEquals("DISABLED", updated.getStatus());
        assertEquals("USD", updated.getCurrency());
    }

    @Test
    void updateShouldRejectMissingRow() {
        when(kbModelDbService.getExistingById(9L)).thenReturn(null);

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.update(9L, saveDto()));

        assertEquals(ErrorCode.MODEL_NOT_FOUND, error.getErrorCode());
        verify(kbModelDbService, never()).updateAll(any());
    }

    @Test
    void updateStatusShouldWriteStatusAndAudit() {
        when(kbModelDbService.getExistingById(3L)).thenReturn(row(3L, "dashscope", "qwen-max", "CHAT", "ENABLED"));
        ModelStatusDto dto = new ModelStatusDto();
        dto.setStatus("disabled");

        ModelItemVO item = service.updateStatus(3L, dto);

        assertEquals("DISABLED", item.getStatus());
        verify(kbModelDbService).updateStatus(3L, "DISABLED");
        verify(auditEventPublisher).publish(eq(AuditActionType.MODEL_DISABLE), eq(AUDIT_OBJECT_TYPE), eq(3L),
                anyString(), anyString());
    }

    /** 字段齐全的收参：覆盖长度四列、价格三列与限额两列 */
    private ModelSaveDto saveDto() {
        ModelSaveDto dto = new ModelSaveDto();
        dto.setVendorCode("dashscope");
        dto.setModelKey("qwen-max");
        dto.setCapabilities(List.of("CHAT", "EMBEDDING"));
        dto.setThinkingEnabled(1);
        dto.setInputLimit(30720);
        dto.setOutputLimit(8192);
        dto.setThinkingInputLimit(32768);
        dto.setThinkingOutputLimit(65536);
        dto.setContextWindow(32768);
        dto.setPriceInPerM(new BigDecimal("20"));
        dto.setPriceInPerMCacheHit(new BigDecimal("2"));
        dto.setPriceOutPerM(new BigDecimal("60"));
        dto.setCurrency("CNY");
        dto.setRpmLimit(600);
        dto.setTpmLimit(120000);
        return dto;
    }

    /** 目录行：状态与能力由形参给出 */
    private WbModel row(Long id, String vendorCode, String modelKey, String capabilities, String status) {
        WbModel row = new WbModel();
        row.setId(id);
        row.setVendorCode(vendorCode);
        row.setModelKey(modelKey);
        row.setCapabilities(capabilities);
        row.setCurrency("CNY");
        row.setStatus(status);
        return row;
    }

    /** 一页目录行（total 与条数一致） */
    private Page<WbModel> pageOf(WbModel... rows) {
        Page<WbModel> page = new Page<>(1, 10);
        page.setRecords(List.of(rows));
        page.setTotal(rows.length);
        return page;
    }

    /** 分页查询条件（页码与页长可空 = 缺省口径） */
    private ModelQueryDto query(String capability, String status, String keyword, Long current, Long size) {
        ModelQueryDto query = new ModelQueryDto();
        query.setCapability(capability);
        query.setStatus(status);
        query.setKeyword(keyword);
        query.setCurrent(current);
        query.setSize(size);
        return query;
    }

    /** 厂商配置体：密钥可空（空表示未配密钥） */
    private ModelVendorProperties.Vendor vendor(String apiKey) {
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setAuthType(AuthType.BEARER);
        vendor.setApiKey(apiKey);
        return vendor;
    }

    /** 取最近一次落库的新增行 */
    private WbModel capturedSave() {
        ArgumentCaptor<WbModel> captor = ArgumentCaptor.forClass(WbModel.class);
        verify(kbModelDbService).save(captor.capture());
        return captor.getValue();
    }

    /** 取最近一次覆盖式更新的行 */
    private WbModel capturedUpdate() {
        ArgumentCaptor<WbModel> captor = ArgumentCaptor.forClass(WbModel.class);
        verify(kbModelDbService).updateAll(captor.capture());
        return captor.getValue();
    }
}
