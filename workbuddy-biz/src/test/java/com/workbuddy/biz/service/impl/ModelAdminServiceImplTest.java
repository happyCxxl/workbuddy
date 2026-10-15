package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbModelDbService;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.domain.model.AuthType;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型管理服务单测（数据访问打桩）：清单过滤与字段映射、新增与编辑的校验逐条、
 * 启停的定点回写与审计、厂商码必须在配置里的校验。
 *
 * @author cxxl
 */
class ModelAdminServiceImplTest {

    /** 审计对象类型（与实现的取值一致） */
    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.MODEL.key();

    private WbModelDbService kbModelDbService;

    private AuditEventPublisher auditEventPublisher;

    private ModelVendorProperties vendorProperties;

    private ModelAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        kbModelDbService = mock(WbModelDbService.class);
        auditEventPublisher = mock(AuditEventPublisher.class);
        vendorProperties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        vendors.put("dashscope", vendor("sk-dashscope"));
        vendors.put("self-ocr", vendor(null));
        vendorProperties.setVendors(vendors);
        service = new ModelAdminServiceImpl(kbModelDbService, vendorProperties, auditEventPublisher);
    }

    @Test
    void listShouldPassFiltersAndMapDirectoryFields() {
        when(kbModelDbService.listByCondition("ENABLED", "qwen")).thenReturn(List.of(
                row(1L, "dashscope", "qwen-max", "CHAT", "ENABLED"),
                row(2L, "dashscope", "qwen-vl-max", "OCR,LAYOUT,TABLE_STRUCTURE", "ENABLED")));
        ModelQueryDto query = new ModelQueryDto();
        query.setCapability("ocr");
        query.setStatus("enabled");
        query.setKeyword("  qwen  ");

        List<ModelItemVO> items = service.list(query);

        verify(kbModelDbService).listByCondition("ENABLED", "qwen");
        assertEquals(1, items.size());
        ModelItemVO item = items.getFirst();
        assertEquals(2L, item.getId());
        assertEquals("dashscope", item.getVendorCode());
        assertEquals("阿里百炼", item.getVendorName());
        assertEquals("qwen-vl-max", item.getModelKey());
        assertEquals(List.of("OCR", "LAYOUT", "TABLE_STRUCTURE"), item.getCapabilities());
        assertEquals("ENABLED", item.getStatus());
    }

    @Test
    void listShouldSkipFiltersWhenAbsent() {
        when(kbModelDbService.listByCondition(null, null)).thenReturn(List.of());

        assertTrue(service.list(null).isEmpty());
        assertTrue(service.list(new ModelQueryDto()).isEmpty());

        verify(kbModelDbService, times(2)).listByCondition(null, null);
    }

    @Test
    void listShouldRejectIllegalDictionaryValues() {
        ModelQueryDto capability = new ModelQueryDto();
        capability.setCapability("VISION");
        KnowledgeException capabilityError = assertThrows(KnowledgeException.class, () -> service.list(capability));

        assertEquals(ErrorCode.PARAM_INVALID, capabilityError.getErrorCode());
        assertTrue(capabilityError.getMessage().contains("能力"), capabilityError.getMessage());

        ModelQueryDto status = new ModelQueryDto();
        status.setStatus("PAUSED");
        KnowledgeException statusError = assertThrows(KnowledgeException.class, () -> service.list(status));

        assertEquals(ErrorCode.PARAM_INVALID, statusError.getErrorCode());
        assertTrue(statusError.getMessage().contains("状态"), statusError.getMessage());
        verify(kbModelDbService, never()).listByCondition(any(), any());
    }

    @Test
    void createShouldFillColumnsAndWriteAudit() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(false);
        ModelSaveDto dto = saveDto();

        ModelItemVO item = service.create(dto);

        WbModel saved = capturedSave();
        assertEquals("dashscope", saved.getVendorCode());
        assertEquals("qwen-max", saved.getModelKey());
        assertEquals("通义千问 Max", saved.getDisplayName());
        assertEquals("CHAT,EMBEDDING", saved.getCapabilities());
        assertEquals(32768, saved.getContextWindow());
        assertEquals(new BigDecimal("20"), saved.getPriceInPerM());
        assertEquals(new BigDecimal("60"), saved.getPriceOutPerM());
        assertEquals("CNY", saved.getCurrency());
        assertEquals(600, saved.getRpmLimit());
        assertEquals(120000, saved.getTpmLimit());
        assertEquals("ENABLED", saved.getStatus());
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), saved.getEffectiveFrom());
        assertEquals(LocalDateTime.of(2026, 12, 31, 23, 59, 59), saved.getEffectiveTo());
        assertEquals("备注", saved.getRemark());
        assertEquals(List.of("CHAT", "EMBEDDING"), item.getCapabilities());
        verify(auditEventPublisher).publish(eq(AuditActionType.MODEL_CREATE), eq(AUDIT_OBJECT_TYPE), isNull(),
                isNull(), anyString());
    }

    @Test
    void createShouldFallBackToModelKeyWhenDisplayNameAbsent() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(false);
        ModelSaveDto dto = saveDto();
        dto.setDisplayName("  ");

        service.create(dto);

        assertEquals("qwen-max", capturedSave().getDisplayName());
    }

    @Test
    void createShouldRejectVendorCodeAbsentFromConfig() {
        ModelSaveDto dto = saveDto();
        dto.setVendorCode("no-such-vendor");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.create(dto));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(error.getMessage().contains("knowledge.model.vendors"), error.getMessage());
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
    void createShouldRejectNegativePriceAndLimit() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(false);
        ModelSaveDto price = saveDto();
        price.setPriceInPerM(new BigDecimal("-1"));
        ModelSaveDto limit = saveDto();
        limit.setRpmLimit(-5);

        KnowledgeException priceError = assertThrows(KnowledgeException.class, () -> service.create(price));
        KnowledgeException limitError = assertThrows(KnowledgeException.class, () -> service.create(limit));

        assertEquals(ErrorCode.PARAM_INVALID, priceError.getErrorCode());
        assertTrue(priceError.getMessage().contains("输入单价"), priceError.getMessage());
        assertTrue(limitError.getMessage().contains("RPM"), limitError.getMessage());
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
    void createShouldRejectIllegalStatusAndCurrency() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(false);
        ModelSaveDto status = saveDto();
        status.setStatus("PAUSED");
        ModelSaveDto currency = saveDto();
        currency.setCurrency("EUR");

        KnowledgeException statusError = assertThrows(KnowledgeException.class, () -> service.create(status));
        KnowledgeException currencyError = assertThrows(KnowledgeException.class, () -> service.create(currency));

        assertEquals(ErrorCode.PARAM_INVALID, statusError.getErrorCode());
        assertTrue(statusError.getMessage().contains("ENABLED"), statusError.getMessage());
        assertTrue(currencyError.getMessage().contains("CNY"), currencyError.getMessage());
        verify(kbModelDbService, never()).save(any());
    }

    @Test
    void createShouldRejectIllegalEffectiveWindow() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(false);
        ModelSaveDto reversed = saveDto();
        reversed.setEffectiveFrom("2026-06-01");
        reversed.setEffectiveTo("2026-05-01");
        ModelSaveDto illegal = saveDto();
        illegal.setEffectiveFrom("2026/06/01");

        KnowledgeException reversedError = assertThrows(KnowledgeException.class, () -> service.create(reversed));
        KnowledgeException illegalError = assertThrows(KnowledgeException.class, () -> service.create(illegal));

        assertEquals(ErrorCode.PARAM_INVALID, reversedError.getErrorCode());
        assertTrue(reversedError.getMessage().contains("早于"), reversedError.getMessage());
        assertTrue(illegalError.getMessage().contains("格式不合法"), illegalError.getMessage());
        verify(kbModelDbService, never()).save(any());
    }

    @Test
    void createShouldRejectMissingRequiredText() {
        ModelSaveDto dto = saveDto();
        dto.setModelKey("   ");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.create(dto));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("模型名"), error.getMessage());
        verify(kbModelDbService, never()).existsVendorAndModelKey(anyString(), anyString(), any());
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
        assertEquals("通义千问 Max", updated.getDisplayName());
        verify(auditEventPublisher).publish(eq(AuditActionType.MODEL_UPDATE), eq(AUDIT_OBJECT_TYPE), eq(5L),
                anyString(), anyString());
    }

    @Test
    void updateShouldKeepStatusAndCurrencyWhenAbsent() {
        WbModel existing = row(7L, "dashscope", "qwen-max", "CHAT", "DISABLED");
        existing.setCurrency("USD");
        when(kbModelDbService.getExistingById(7L)).thenReturn(existing);
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", 7L)).thenReturn(false);
        ModelSaveDto dto = saveDto();
        dto.setStatus(null);
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

    @Test
    void updateStatusShouldRejectBlankOrIllegalStatus() {
        when(kbModelDbService.getExistingById(3L)).thenReturn(row(3L, "dashscope", "qwen-max", "CHAT", "ENABLED"));
        ModelStatusDto blank = new ModelStatusDto();
        ModelStatusDto illegal = new ModelStatusDto();
        illegal.setStatus("PAUSED");

        KnowledgeException blankError = assertThrows(KnowledgeException.class, () -> service.updateStatus(3L, blank));
        KnowledgeException illegalError = assertThrows(KnowledgeException.class,
                () -> service.updateStatus(3L, illegal));

        assertEquals(ErrorCode.PARAM_INVALID, blankError.getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID, illegalError.getErrorCode());
        verify(kbModelDbService, never()).updateStatus(any(), any());
        verify(auditEventPublisher, never()).publish(any(), anyString(), any(), any(), any());
    }

    /** 字段齐全的收参：覆盖价格四列、限额两列与生效区间 */
    private ModelSaveDto saveDto() {
        ModelSaveDto dto = new ModelSaveDto();
        dto.setVendorCode("dashscope");
        dto.setModelKey("qwen-max");
        dto.setDisplayName("通义千问 Max");
        dto.setCapabilities(List.of("CHAT", "EMBEDDING"));
        dto.setContextWindow(32768);
        dto.setPriceInPerM(new BigDecimal("20"));
        dto.setPriceOutPerM(new BigDecimal("60"));
        dto.setPriceImagePerPage(new BigDecimal("0.02"));
        dto.setPricePerThousandCalls(new BigDecimal("0.0007"));
        dto.setCurrency("CNY");
        dto.setRpmLimit(600);
        dto.setTpmLimit(120000);
        dto.setStatus("ENABLED");
        dto.setEffectiveFrom("2026-01-01");
        dto.setEffectiveTo("2026-12-31T23:59:59");
        dto.setRemark("备注");
        return dto;
    }

    /** 目录行：状态与能力由形参给出 */
    private WbModel row(Long id, String vendorCode, String modelKey, String capabilities, String status) {
        WbModel row = new WbModel();
        row.setId(id);
        row.setVendorCode(vendorCode);
        row.setModelKey(modelKey);
        row.setDisplayName(vendorCode + "-" + modelKey);
        row.setCapabilities(capabilities);
        row.setCurrency("CNY");
        row.setStatus(status);
        return row;
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
