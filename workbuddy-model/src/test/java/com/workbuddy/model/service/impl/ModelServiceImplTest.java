package com.workbuddy.model.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.common.convert.model.ModelConverterImpl;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.enums.model.AuthType;
import com.workbuddy.common.dto.request.model.ModelQueryDTO;
import com.workbuddy.common.dto.request.model.ModelSaveDTO;
import com.workbuddy.common.dto.request.model.ModelUpdateDTO;
import com.workbuddy.common.dto.request.model.ModelStatusDTO;
import com.workbuddy.common.dto.response.model.ModelItemVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.security.audit.AuditEventPublisher;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.db.WbModelDbService;
import com.workbuddy.model.service.support.catalog.ModelRegistryRefresher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型管理服务单测（数据访问打桩）：清单过滤与字段映射、新增与编辑的校验逐条、
 * 启停的定点回写与审计、厂商码必须在配置里的校验。
 */
class ModelServiceImplTest {

    /** 审计对象类型（与实现的取值一致） */
    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.MODEL.key();

    private WbModelDbService kbModelDbService;

    private AuditEventPublisher auditEventPublisher;

    private ModelVendorProperties vendorProperties;

    private ModelRegistryRefresher refresher;

    private ModelServiceImpl service;

    @BeforeEach
    void setUp() {
        kbModelDbService = mock(WbModelDbService.class);
        auditEventPublisher = mock(AuditEventPublisher.class);
        refresher = mock(ModelRegistryRefresher.class);
        vendorProperties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        vendors.put("dashscope", vendor("sk-dashscope"));
        vendors.put("self-ocr", vendor(null));
        vendors.get("dashscope").setDisplayName("阿里百炼");
        vendorProperties.setVendors(vendors);
        service = new ModelServiceImpl(kbModelDbService, vendorProperties, auditEventPublisher, refresher,
                new ModelConverterImpl());
    }

    /**
     * 目录分页：筛选条件透传与条目字段映射。
     *
     * <p>守护 {@code page}：条件或字段错位即失败。
     */
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

    /**
     * 目录条目：长度、价格、限额、思考标记与币种逐列映射。
     *
     * <p>守护 {@code toItem}：漏搬任一列都会让模型配置页显示空值。
     */
    @Test
    void pageShouldMapNumericColumns() {
        WbModel full = row(4L, "dashscope", "qwen-max", "CHAT", "ENABLED");
        full.setThinkingEnabled(1);
        full.setInputLimit(30720);
        full.setOutputLimit(8192);
        full.setThinkingInputLimit(32768);
        full.setThinkingOutputLimit(65536);
        full.setContextWindow(32768);
        full.setPriceInPerM(new BigDecimal("20"));
        full.setPriceInPerMCacheHit(new BigDecimal("2"));
        full.setPriceOutPerM(new BigDecimal("60"));
        full.setCurrency("CNY");
        full.setRpmLimit(600);
        full.setTpmLimit(120000);
        when(kbModelDbService.pageByCondition(1, 10, null, null, null)).thenReturn(pageOf(full));

        ModelItemVO item = service.page(query(null, null, null, null, null)).getRecords().getFirst();

        assertEquals(1, item.getThinkingEnabled());
        assertEquals(30720, item.getInputLimit());
        assertEquals(8192, item.getOutputLimit());
        assertEquals(32768, item.getThinkingInputLimit());
        assertEquals(65536, item.getThinkingOutputLimit());
        assertEquals(32768, item.getContextWindow());
        assertEquals(new BigDecimal("20"), item.getPriceInPerM());
        assertEquals(new BigDecimal("2"), item.getPriceInPerMCacheHit());
        assertEquals(new BigDecimal("60"), item.getPriceOutPerM());
        assertEquals("CNY", item.getCurrency());
        assertEquals(600, item.getRpmLimit());
        assertEquals(120000, item.getTpmLimit());
    }

    /**
     * 厂商展示名：未配置的厂商回落厂商码，厂商码为空时留空。
     *
     * <p>守护 {@code vendorName}：回落成空串会让列表的厂商列变成空白。
     */
    @Test
    void pageShouldFallBackToVendorCodeWhenUnconfigured() {
        when(kbModelDbService.pageByCondition(1, 10, null, null, null))
                .thenReturn(pageOf(row(5L, "unconfigured", "m-1", "CHAT", "ENABLED"),
                        row(6L, null, "m-2", "CHAT", "ENABLED")));

        List<ModelItemVO> items = service.page(query(null, null, null, null, null)).getRecords();

        assertEquals("unconfigured", items.get(0).getVendorName());
        assertNull(items.get(1).getVendorName());
    }

    /** 页码与页长缺省按 1 / 10 兜底，筛选条件留空按不过滤下发 */
    /**
     * 目录分页：页码页长缺省、空关键字不过滤。
     *
     * <p>守护 {@code page}：缺省口径写错即失败。
     */
    @Test
    void pageShouldDefaultPaginationWhenAbsent() {
        when(kbModelDbService.pageByCondition(1, 10, null, null, null)).thenReturn(pageOf());

        IPage<ModelItemVO> result = service.page(query(null, null, "   ", null, null));

        assertTrue(result.getRecords().isEmpty());
        verify(kbModelDbService).pageByCondition(1, 10, null, null, null);
    }

    /**
     * 新增模型：业务列逐列落库并写 CREATE 审计。
     *
     * <p>守护 {@code create}：漏搬任一列即失败。
     */
    @Test
    void createShouldFillColumnsAndWriteAudit() {
        ModelSaveDTO dto = saveDto();

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
                anyString());
        verify(refresher).refreshAfterCommit();
    }

    /**
     * 新增模型：思考标记缺省按 0 落库。
     *
     * <p>守护 {@code create}：默认值写成 null 即失败。
     */
    @Test
    void createShouldDefaultThinkingFlagToDisabled() {
        ModelSaveDTO dto = saveDto();
        dto.setThinkingEnabled(null);

        service.create(dto);

        assertEquals(0, capturedSave().getThinkingEnabled());
    }

    /**
     * 新增模型：能力取值不在字典内按 40001 拒绝。
     *
     * <p>守护 {@code create}：未知能力落库即失败。
     */
    @Test
    void createShouldRejectIllegalCapability() {
        ModelSaveDTO unknown = saveDto();
        unknown.setCapabilities(List.of("VISION"));

        WorkbuddyException unknownError = assertThrows(WorkbuddyException.class, () -> service.create(unknown));

        assertEquals(ErrorCode.PARAM_INVALID, unknownError.getErrorCode());
        assertTrue(unknownError.getMessage().contains("VISION"), unknownError.getMessage());
        verify(kbModelDbService, never()).save(any());
    }

    /**
     * 新增模型：状态恒为启用。
     *
     * <p>守护 {@code create}：新增即停用即失败。
     */
    @Test
    void createShouldAlwaysSaveEnabled() {
        service.create(saveDto());

        assertEquals("ENABLED", capturedSave().getStatus());
    }

    /**
     * 编辑模型：业务列逐列覆盖、币种按收参、状态保持。
     *
     * <p>守护 {@code update}：漏搬任一列即失败。
     */
    @Test
    void updateShouldOverwriteColumns() {
        when(kbModelDbService.getExistingById(5L))
                .thenReturn(row(5L, "dashscope", "qwen-max", "CHAT", "ENABLED"));
        ModelUpdateDTO dto = updateDto();
        dto.setPriceInPerM(null);

        ModelItemVO item = service.update(5L, dto);

        assertEquals(5L, item.getId());
        WbModel updated = capturedUpdate();
        assertEquals(5L, updated.getId());
        // 业务列逐列覆盖（漏搬任一列都会让编辑静默失效）
        assertEquals("dashscope", updated.getVendorCode());
        assertEquals("qwen-max", updated.getModelKey());
        assertEquals("CHAT,EMBEDDING", updated.getCapabilities());
        assertEquals(1, updated.getThinkingEnabled());
        assertEquals(30720, updated.getInputLimit());
        assertEquals(8192, updated.getOutputLimit());
        assertEquals(32768, updated.getContextWindow());
        assertEquals("CNY", updated.getCurrency());
        assertNull(updated.getPriceInPerM());
        verify(auditEventPublisher).publish(eq(AuditActionType.MODEL_UPDATE), eq(AUDIT_OBJECT_TYPE), eq(5L),
                anyString());
    }

    /** 编辑不改状态：停用行编辑后保持停用（启停只走专用接口）；币种按收参原样覆盖 */
    /**
     * 编辑模型：状态不被编辑改写，停用行仍停用。
     *
     * <p>守护 {@code update}：编辑顺带改状态即失败。
     */
    @Test
    void updateShouldKeepStatusWhenAbsent() {
        WbModel existing = row(7L, "dashscope", "qwen-max", "CHAT", "DISABLED");
        existing.setCurrency("USD");
        when(kbModelDbService.getExistingById(7L)).thenReturn(existing);
        ModelUpdateDTO dto = updateDto();

        service.update(7L, dto);

        WbModel updated = capturedUpdate();
        assertEquals("DISABLED", updated.getStatus());
        assertEquals("CNY", updated.getCurrency());
    }

    /**
     * 编辑模型：行不存在按 40453 拒绝且不写库。
     *
     * <p>守护 {@code update}：空行可编辑即失败。
     */
    @Test
    void updateShouldRejectMissingRow() {
        when(kbModelDbService.getExistingById(9L)).thenReturn(null);

        WorkbuddyException error = assertThrows(WorkbuddyException.class, () -> service.update(9L, updateDto()));

        assertEquals(ErrorCode.MODEL_NOT_FOUND, error.getErrorCode());
        verify(kbModelDbService, never()).updateModel(any());
    }

    /**
     * 启停：状态大写归一、写库并记 DISABLE 审计。
     *
     * <p>守护 {@code updateStatus}：动作或状态写错即失败。
     */
    @Test
    void updateStatusShouldWriteStatusAndAudit() {
        when(kbModelDbService.getExistingById(3L)).thenReturn(row(3L, "dashscope", "qwen-max", "CHAT", "ENABLED"));
        ModelStatusDTO dto = new ModelStatusDTO();
        dto.setStatus("disabled");

        ModelItemVO item = service.updateStatus(3L, dto);

        assertEquals("DISABLED", item.getStatus());
        verify(kbModelDbService).updateStatus(3L, "DISABLED");
        verify(auditEventPublisher).publish(eq(AuditActionType.MODEL_DISABLE), eq(AUDIT_OBJECT_TYPE), eq(3L),
                anyString());
    }

    /**
     * 启停：状态为空按 40001 拒绝；启用时审计动作是 ENABLE。
     *
     * <p>守护 {@code updateStatus} 的入参防线与动作判定：空状态当成功、启用记成停用都会误导审计。
     */
    @Test
    void updateStatusShouldRejectBlankAndPublishEnable() {
        when(kbModelDbService.getExistingById(3L)).thenReturn(row(3L, "dashscope", "qwen-max", "CHAT", "DISABLED"));
        ModelStatusDTO blank = new ModelStatusDTO();
        blank.setStatus("   ");
        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> service.updateStatus(3L, blank));
        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        verify(kbModelDbService, never()).updateStatus(any(), any());

        ModelStatusDTO enable = new ModelStatusDTO();
        enable.setStatus("enabled");
        assertEquals("ENABLED", service.updateStatus(3L, enable).getStatus());
        verify(auditEventPublisher).publish(eq(AuditActionType.MODEL_ENABLE), eq(AUDIT_OBJECT_TYPE), eq(3L),
                anyString());
    }

    /** 厂商码不在配置里：按 MODEL_VENDOR_UNKNOWN 拒绝且不写库（{@code vendorShouldBeConfigured}）。 */
    @Test
    void createShouldRejectUnknownVendor() {
        ModelSaveDTO dto = saveDto();
        dto.setVendorCode("no-such-vendor");

        WorkbuddyException error = assertThrows(WorkbuddyException.class, () -> service.create(dto));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        verify(kbModelDbService, never()).save(any(WbModel.class));
    }

    /** 厂商码大小写不敏感，落库统一小写（{@code vendorShouldBeNormalized}）。 */
    @Test
    void createShouldNormalizeVendorCase() {
        ModelSaveDTO dto = saveDto();
        dto.setVendorCode("DASHSCOPE");

        service.create(dto);

        assertEquals("dashscope", capturedSave().getVendorCode());
    }

    /** 同厂商同模型名：预检命中按 40001 拒绝且不写库（{@code duplicateShouldBeRejected}）。 */
    @Test
    void createShouldRejectDuplicateKey() {
        when(kbModelDbService.existsVendorAndModelKey("dashscope", "qwen-max", null)).thenReturn(true);

        WorkbuddyException error = assertThrows(WorkbuddyException.class, () -> service.create(saveDto()));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        verify(kbModelDbService, never()).save(any(WbModel.class));
    }

    /** 字段齐全的新增收参：覆盖长度四列、价格三列与限额两列 */
    private ModelSaveDTO saveDto() {
        ModelSaveDTO dto = new ModelSaveDTO();
        applyFullFields(dto);
        return dto;
    }

    /** 字段齐全的编辑收参：字段口径与新增一致 */
    private ModelUpdateDTO updateDto() {
        ModelUpdateDTO dto = new ModelUpdateDTO();
        applyFullFields(dto);
        return dto;
    }

    /** 全字段赋值（新增 / 编辑收参共用） */
    private void applyFullFields(ModelSaveDTO dto) {
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
    private ModelQueryDTO query(String capability, String status, String keyword, Long current, Long size) {
        ModelQueryDTO query = new ModelQueryDTO();
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
        verify(kbModelDbService).updateModel(captor.capture());
        return captor.getValue();
    }
}
