package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDTO;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略版本增删改查用例：类型白名单 / 列表（启用中 vs 全部）/ 创建 / 编辑=复制新版本 / 启停 /
 * 删除（有绑定引用禁删）。
 *
 * <p>各环节配置校验用例在 {@link StrategyVersionValidationTest}；装配与夹具数据见
 * {@link StrategyVersionTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class StrategyVersionCrudTest {

    private StrategyVersionTestFixture fixture;

    private StrategyVersionServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new StrategyVersionTestFixture();
        service = fixture.service();
    }

    /**
     * 校验点：{@code listEnabledShouldReturnMappedVersions}。
     */
    @Test
    void listEnabledShouldReturnMappedVersions() {
        when(fixture.strategyVersionDbService.listEnabledByType("CHUNK"))
                .thenReturn(List.of(fixture.row(9L, "v2", "ACTIVE")));

        List<StrategyVersionVO> vos = service.list("CHUNK", false);

        assertEquals(java.time.LocalDateTime.of(2026, 10, 9, 10, 0), vos.getFirst().getCreateTime());

        assertEquals(1, vos.size());
        assertEquals(9L, vos.getFirst().getId());
        assertEquals("ACTIVE", vos.getFirst().getStatus());
        verify(fixture.strategyVersionDbService, never()).listByType(any());
    }

    /**
     * 校验点：{@code listWithIncludeInactiveShouldReturnAll}。
     */
    @Test
    void listWithIncludeInactiveShouldReturnAll() {
        when(fixture.strategyVersionDbService.listByType("CHUNK"))
                .thenReturn(List.of(fixture.row(10L, "v1", "INACTIVE")));

        List<StrategyVersionVO> vos = service.list("CHUNK", true);

        assertEquals(1, vos.size());
        assertEquals("INACTIVE", vos.getFirst().getStatus());
        verify(fixture.strategyVersionDbService, never()).listEnabledByType(any());
    }

    /**
     * 校验点：{@code listWithUnknownTypeShouldReject}。
     */
    @Test
    void listWithUnknownTypeShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.list("INDEX", false));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(fixture.strategyVersionDbService, never()).listEnabledByType(any());
    }

    /**
     * 校验点：{@code listWithBlankTypeShouldReject}。
     */
    @Test
    void listWithBlankTypeShouldReject() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.list("  ", false));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(fixture.strategyVersionDbService, never()).listEnabledByType(any());
    }

    /**
     * 校验点：{@code createShouldSaveAsActive}。
     */
    @Test
    void createShouldSaveAsActive() {
        when(fixture.strategyVersionDbService.save(any(WbPipelineStrategyVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineStrategyVersion.class).setId(11L);
            return true;
        });

        StrategyVersionVO vo = service.create(fixture.createDto());

        assertEquals(11L, vo.getId());
        assertEquals("ACTIVE", vo.getStatus());
    }

    /**
     * 校验点：{@code createWithUnknownTypeShouldReject}。
     */
    @Test
    void createWithUnknownTypeShouldReject() {
        StrategyVersionCreateDTO dto = fixture.chunkDto("{\"routes\":{}}");
        dto.setType("INDEX");
        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.create(dto));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(fixture.strategyVersionDbService, never()).save(any());
    }

    /**
     * 校验点：{@code createDuplicateKeyShouldReject}。
     */
    @Test
    void createDuplicateKeyShouldReject() {
        when(fixture.strategyVersionDbService.save(any(WbPipelineStrategyVersion.class)))
                .thenThrow(new DuplicateKeyException("uk_type_name_version"));

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.create(fixture.createDto()));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("同环节同名版本已存在", e.getMessage());
    }

    /**
     * 校验点：{@code updateShouldCopyAsNewVersion}。
     */
    @Test
    void updateShouldCopyAsNewVersion() {
        // 策略版本行不可变：编辑 = 复制新行（旧行原样保留，新行按 dto 注册且默认 ACTIVE）
        WbPipelineStrategyVersion existing = fixture.row(9L, "v1", "INACTIVE");
        when(fixture.strategyVersionDbService.getById(9L)).thenReturn(existing);

        StrategyVersionVO vo = service.update(9L, fixture.updateDto());

        assertEquals("chunk-strict", vo.getName());
        assertEquals("v2", vo.getVersion());
        assertEquals("{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\"}}}", vo.getConfigSnapshot());
        assertEquals("ACTIVE", vo.getStatus());
        ArgumentCaptor<WbPipelineStrategyVersion> captor = ArgumentCaptor.forClass(WbPipelineStrategyVersion.class);
        verify(fixture.strategyVersionDbService).save(captor.capture());
        assertEquals("CHUNK", captor.getValue().getType());
        // 旧行未被改动
        verify(fixture.strategyVersionDbService, never()).updateById(any(WbPipelineStrategyVersion.class));
        assertEquals("chunk-hybrid", existing.getName());
        assertEquals("v1", existing.getVersion());
        assertEquals("INACTIVE", existing.getStatus());
    }

    /**
     * 校验点：{@code updateNotFoundShouldReject}。
     */
    @Test
    void updateNotFoundShouldReject() {
        when(fixture.strategyVersionDbService.getById(9L)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.update(9L, fixture.updateDto()));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(fixture.strategyVersionDbService, never()).save(any());
        verify(fixture.strategyVersionDbService, never()).updateById(any());
    }

    /**
     * 校验点：{@code enableShouldSetActive}。
     */
    @Test
    void enableShouldSetActive() {
        when(fixture.strategyVersionDbService.getById(9L))
                .thenReturn(fixture.row(9L, "v1", "INACTIVE"));

        StrategyVersionVO vo = service.enable(9L);

        assertEquals("ACTIVE", vo.getStatus());
        verify(fixture.strategyVersionDbService).updateById(any(WbPipelineStrategyVersion.class));
    }

    /**
     * 校验点：{@code disableShouldSetInactive}。
     */
    @Test
    void disableShouldSetInactive() {
        when(fixture.strategyVersionDbService.getById(9L))
                .thenReturn(fixture.row(9L, "v1", "ACTIVE"));

        StrategyVersionVO vo = service.disable(9L);

        assertEquals("INACTIVE", vo.getStatus());
        verify(fixture.strategyVersionDbService).updateById(any(WbPipelineStrategyVersion.class));
    }

    /**
     * 校验点：{@code enableNotFoundShouldReject}。
     */
    @Test
    void enableNotFoundShouldReject() {
        when(fixture.strategyVersionDbService.getById(9L)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.enable(9L));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(fixture.strategyVersionDbService, never()).updateById(any());
    }

    /**
     * 校验点：{@code deleteShouldRemoveWhenNoBindingReference}。
     */
    @Test
    void deleteShouldRemoveWhenNoBindingReference() {
        when(fixture.strategyVersionDbService.getById(9L))
                .thenReturn(fixture.row(9L, "v1", "ACTIVE"));
        when(fixture.strategyBindingDbService.existsByStrategyVersionId(9L)).thenReturn(false);
        when(fixture.strategyVersionDbService.removeById(9L)).thenReturn(true);

        assertTrue(service.delete(9L));
        verify(fixture.strategyVersionDbService).removeById(9L);

        // 受影响 0 行（并发下已被别人删掉）不能报成功
        when(fixture.strategyVersionDbService.removeById(9L)).thenReturn(false);
        assertFalse(service.delete(9L));
    }

    /**
     * 校验点：{@code deleteShouldRejectWhenBoundByKnowledgeBase}。
     */
    @Test
    void deleteShouldRejectWhenBoundByKnowledgeBase() {
        // 策略版本行不可变：有绑定引用禁物理删除（停用代替删除）
        when(fixture.strategyVersionDbService.getById(9L))
                .thenReturn(fixture.row(9L, "v1", "ACTIVE"));
        when(fixture.strategyBindingDbService.existsByStrategyVersionId(9L)).thenReturn(true);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.delete(9L));
        assertEquals(ErrorCode.STRATEGY_BOUND_DELETE_FORBIDDEN, e.getErrorCode());
        verify(fixture.strategyVersionDbService, never()).removeById(any(Serializable.class));
    }

    /**
     * 校验点：{@code deleteNotFoundShouldReject}。
     */
    @Test
    void deleteNotFoundShouldReject() {
        when(fixture.strategyVersionDbService.getById(9L)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.delete(9L));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(fixture.strategyVersionDbService, never()).removeById(any(Serializable.class));
    }

    /**
     * 校验点：{@code listRetrievalTypeShouldBeSupported}。
     */
    @Test
    void listRetrievalTypeShouldBeSupported() {
        when(fixture.strategyVersionDbService.listEnabledByType("RETRIEVAL")).thenReturn(List.of());

        service.list("RETRIEVAL", false);

        verify(fixture.strategyVersionDbService).listEnabledByType("RETRIEVAL");
        assertTrue(mockingDetails(fixture.strategyVersionDbService).getInvocations().stream()
                .anyMatch(inv -> "listEnabledByType".equals(inv.getMethod().getName())));
    }
}
