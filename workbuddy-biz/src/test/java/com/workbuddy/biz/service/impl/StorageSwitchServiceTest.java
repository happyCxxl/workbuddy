package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbStorageSource;
import com.workbuddy.common.dto.response.setting.StorageSwitchPreviewVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchResultVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.enums.storage.StorageSourceStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.infra.storage.StorageSourceDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存储切换服务用例：切换预览不落库与目标的可用性校验 / 切换的先清后置、探测回写、注册与审计。
 *
 * <p>启动初始化与清单用例在 {@link StorageSourceLifecycleTest}；装配与夹具数据见
 * {@link StorageSourceTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class StorageSwitchServiceTest {

    @TempDir
    Path root;

    private StorageSourceTestFixture fixture;

    private StorageSourceServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new StorageSourceTestFixture(root);
        service = fixture.service();
    }

    /**
     * 切换预览：影响面计数与两侧数据源信息逐列下发，且不落库。
     *
     * <p>守护 {@code previewCurrent}：漏字段或偷偷落库即失败。
     */
    @Test
    void previewShouldCountImpactWithoutPersisting() {
        when(fixture.storageSourceDbService.getById(StorageSourceTestFixture.LOCAL_ID))
                .thenReturn(fixture.localRow(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", false));
        when(fixture.storageRouter.currentSourceId()).thenReturn(StorageSourceTestFixture.OTHER_ID);
        fixture.stubActiveTasks(fixture.task(10L, PipelineTaskStatus.QUEUED),
                fixture.task(11L, PipelineTaskStatus.RUNNING),
                fixture.task(12L, PipelineTaskStatus.QUEUED));
        when(fixture.chainStorageSupport.chainSourceIdsOf(List.of(10L, 11L, 12L)))
                .thenReturn(Map.of(10L, StorageSourceTestFixture.OTHER_ID, 11L, StorageSourceTestFixture.OTHER_ID,
                        12L, StorageSourceTestFixture.LOCAL_ID));
        when(fixture.chainStorageSupport.sourceName(StorageSourceTestFixture.OTHER_ID)).thenReturn("旧数据源");
        when(fixture.storageRouter.typeOf(StorageSourceTestFixture.OTHER_ID)).thenReturn(StorageType.MINIO);

        StorageSwitchPreviewVO vo = service.previewCurrent(StorageSourceTestFixture.LOCAL_ID);

        assertEquals(StorageSourceTestFixture.LOCAL_ID, vo.getTargetSourceId());
        assertEquals("本地磁盘", vo.getTargetName());
        assertEquals(StorageType.LOCAL.getCode(), vo.getTargetType());
        assertEquals(StorageSourceTestFixture.OTHER_ID, vo.getCurrentSourceId());
        assertEquals("旧数据源", vo.getCurrentName());
        assertEquals(StorageType.MINIO.getCode(), vo.getCurrentType());
        assertEquals(1L, vo.getQueuedCount());
        assertEquals(1L, vo.getRunningCount());
        assertTrue(vo.getMessage().contains("旧数据源"), vo.getMessage());
        assertTrue(vo.getMessage().contains("1 个排队中"), vo.getMessage());
        // 预览不落库、不切换、不落审计
        verify(fixture.storageSourceDbService, never()).clearCurrent();
        verify(fixture.storageSourceDbService, never()).markCurrent(any());
        verify(fixture.storageSourceDbService, never()).updateById(any());
        verify(fixture.storageRouter, never()).markCurrent(any());
        verify(fixture.kbAuditLogDbService, never()).saveAudit(any(), anyString(), any(), any(), any());
    }

    /**
     * 切换预览：目标已停用按 40458 拒绝。
     *
     * <p>守护 {@code previewCurrent}：停用目标可预览即失败。
     */
    @Test
    void previewShouldRejectDisabledTarget() {
        WbStorageSource stored = fixture.localRow(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", false);
        stored.setStatus(StorageSourceStatus.DISABLED.getCode());
        when(fixture.storageSourceDbService.getById(StorageSourceTestFixture.LOCAL_ID)).thenReturn(stored);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.previewCurrent(StorageSourceTestFixture.LOCAL_ID));

        assertEquals(ErrorCode.STORAGE_SOURCE_ILLEGAL, e.getErrorCode());
        assertTrue(e.getMessage().contains("请先启用再切换"), e.getMessage());
    }

    /**
     * 切换预览：参数不全按 40001 拒绝并逐个报缺失键。
     *
     * <p>守护 {@code previewCurrent}：缺参数被放行即失败。
     */
    @Test
    void previewShouldRejectIncompleteParamsWith40001() {
        Map<String, String> incomplete = new LinkedHashMap<>();
        incomplete.put(StorageSourceDef.KEY_ROOT_DIR, root.resolve("incomplete").toString());
        incomplete.put(StorageSourceDef.KEY_FILE_DIR, "files");
        when(fixture.storageSourceDbService.getById(StorageSourceTestFixture.LOCAL_ID))
                .thenReturn(fixture.row(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", StorageType.LOCAL,
                        incomplete, StorageSourceStatus.ENABLED, 0));

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.previewCurrent(StorageSourceTestFixture.LOCAL_ID));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        // 缺失的参数名逐个报出
        assertTrue(e.getMessage().contains(StorageSourceDef.KEY_ARTIFACT_DIR), e.getMessage());
    }

    /**
     * 切换预览：数据源不存在按 40457 拒绝。
     *
     * <p>守护 {@code previewCurrent}：空行可预览即失败。
     */
    @Test
    void previewShouldRejectUnknownIdWith40457() {
        when(fixture.storageSourceDbService.getById(StorageSourceTestFixture.OTHER_ID)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.previewCurrent(StorageSourceTestFixture.OTHER_ID));

        assertEquals(ErrorCode.STORAGE_SOURCE_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 切换目标类型码值无法识别：按 40054 拒绝，不注册也不改库。
     *
     * <p>守护 {@code defOf} 的类型兜底：拿着 null 类型去建存储会把配置错误报成连接失败。
     */
    @Test
    void switchShouldRejectUnrecognizedTargetType() {
        WbStorageSource broken = fixture.localRow(StorageSourceTestFixture.LOCAL_ID, "未知源", false);
        broken.setStorageType("oss");
        when(fixture.storageSourceDbService.getById(StorageSourceTestFixture.LOCAL_ID)).thenReturn(broken);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.switchCurrent(StorageSourceTestFixture.LOCAL_ID, StorageSourceTestFixture.OPERATOR));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        verify(fixture.storageRouter, never()).register(any(StorageSourceDef.class));
        verify(fixture.storageSourceDbService, never()).clearCurrent();
    }

    /**
     * 切换：注册、探活、清旧置新、结论与审计落库，结果行字段齐全。
     *
     * <p>守护 {@code switchCurrent}：漏字段或漏审计即失败。
     */
    @Test
    void switchShouldPersistRegisterAndAudit() {
        when(fixture.storageSourceDbService.getById(StorageSourceTestFixture.LOCAL_ID))
                .thenReturn(fixture.localRow(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", false));
        when(fixture.storageRouter.currentSourceId()).thenReturn(StorageSourceTestFixture.OTHER_ID);
        fixture.stubActiveTasks(fixture.task(10L, PipelineTaskStatus.QUEUED));
        when(fixture.chainStorageSupport.chainSourceIdsOf(List.of(10L)))
                .thenReturn(Map.of(10L, StorageSourceTestFixture.OTHER_ID));
        when(fixture.chainStorageSupport.sourceName(StorageSourceTestFixture.OTHER_ID)).thenReturn("旧数据源");

        StorageSwitchResultVO vo = service.switchCurrent(StorageSourceTestFixture.LOCAL_ID,
                StorageSourceTestFixture.OPERATOR);

        assertEquals(StorageSourceTestFixture.LOCAL_ID, vo.getSwitchedSourceId());
        assertEquals(StorageSourceTestFixture.LOCAL_ID, vo.getCurrentSourceId());
        assertEquals("本地磁盘", vo.getCurrentName());
        assertEquals(StorageType.LOCAL.getCode(), vo.getCurrentType());
        assertEquals(StorageSourceTestFixture.LOCAL_ID, vo.getTargetSourceId());
        assertEquals("本地磁盘", vo.getTargetName());
        assertEquals(1L, vo.getQueuedCount());
        assertEquals(0L, vo.getRunningCount());
        assertTrue(vo.getMessage().contains("已切换到 本地磁盘"), vo.getMessage());
        assertTrue(vo.getMessage().contains("旧数据源"), vo.getMessage());
        verify(fixture.storageRouter).register(any(StorageSourceDef.class));
        // 先清后置，全表至多一行为 1；探活结论随这次切换一起落库
        InOrder order = inOrder(fixture.storageSourceDbService);
        order.verify(fixture.storageSourceDbService).clearCurrent();
        order.verify(fixture.storageSourceDbService).markCurrent(StorageSourceTestFixture.LOCAL_ID);
        // 注册与探活先于 DB 事务；进程内 markCurrent 在 DB 提交后
        InOrder externalFirst = inOrder(fixture.storageRouter, fixture.storageSourceDbService);
        externalFirst.verify(fixture.storageRouter).register(any(StorageSourceDef.class));
        externalFirst.verify(fixture.storageSourceDbService).clearCurrent();
        externalFirst.verify(fixture.storageRouter).markCurrent(StorageSourceTestFixture.LOCAL_ID);
        verify(fixture.storageSourceDbService).recordProbe(eq(StorageSourceTestFixture.LOCAL_ID), eq(true), any());
        verify(fixture.storageRouter).markCurrent(StorageSourceTestFixture.LOCAL_ID);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(fixture.kbAuditLogDbService).saveAudit(eq(AuditActionType.STORAGE_SWITCH),
                eq(AuditObjectType.STORAGE_SOURCE.key()), eq(StorageSourceTestFixture.LOCAL_ID), anyString(),
                after.capture());
        assertTrue(after.getValue().contains("本地磁盘"), after.getValue());
        assertTrue(after.getValue().contains("排队中 1"), after.getValue());
    }

    /**
     * 切换：目标即当前启用时幂等返回，不写库。
     *
     * <p>守护 {@code switchCurrent}：重复切换落库即失败。
     */
    @Test
    void switchShouldDoNothingWhenAlreadyCurrent() {
        when(fixture.storageSourceDbService.getById(StorageSourceTestFixture.LOCAL_ID))
                .thenReturn(fixture.localRow(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", true));
        when(fixture.storageRouter.currentSourceId()).thenReturn(StorageSourceTestFixture.LOCAL_ID);

        StorageSwitchResultVO vo = service.switchCurrent(StorageSourceTestFixture.LOCAL_ID,
                StorageSourceTestFixture.OPERATOR);

        assertEquals(StorageSourceTestFixture.LOCAL_ID, vo.getSwitchedSourceId());
        assertTrue(vo.getMessage().contains("已经是"), vo.getMessage());
        verify(fixture.storageSourceDbService, never()).clearCurrent();
        verify(fixture.storageSourceDbService, never()).markCurrent(any());
        verify(fixture.storageSourceDbService, never()).recordProbe(any(), anyBoolean(), any());
        verify(fixture.storageRouter, never()).markCurrent(any());
    }

    /**
     * 切换：探活失败整体不落库（结论与审计都不写）。
     *
     * <p>守护 {@code switchCurrent}：失败留半截状态即失败。
     */
    @Test
    void switchShouldNotPersistWhenProbeFails() {
        WbStorageSource stored = fixture.row(StorageSourceTestFixture.LOCAL_ID, "演示 MinIO", StorageType.MINIO,
                fixture.minioParams(), StorageSourceStatus.ENABLED, 0);
        when(fixture.storageSourceDbService.getById(StorageSourceTestFixture.LOCAL_ID)).thenReturn(stored);
        when(fixture.storageRouter.currentSourceId()).thenReturn(StorageSourceTestFixture.OTHER_ID);
        when(fixture.pipelineTaskDbService.listByStatuses(any())).thenReturn(List.of());

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.switchCurrent(StorageSourceTestFixture.LOCAL_ID, StorageSourceTestFixture.OPERATOR));

        assertEquals(ErrorCode.STORAGE_SOURCE_PROBE_FAILED, e.getErrorCode());
        verify(fixture.storageSourceDbService, never()).clearCurrent();
        verify(fixture.storageSourceDbService, never()).markCurrent(any());
        // 探活失败整体回滚：探测结论也不落库，不留半截状态
        verify(fixture.storageSourceDbService, never()).recordProbe(any(), anyBoolean(), any());
        verify(fixture.kbAuditLogDbService, never()).saveAudit(any(), anyString(), any(), any(), any());
    }

    /**
     * 切换：目标已停用按 40458 拒绝且不写库。
     *
     * <p>守护 {@code switchCurrent}：停用目标可切换即失败。
     */
    @Test
    void switchShouldRejectDisabledTargetWith40458() {
        WbStorageSource stored = fixture.localRow(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", false);
        stored.setStatus(StorageSourceStatus.DISABLED.getCode());
        when(fixture.storageSourceDbService.getById(StorageSourceTestFixture.LOCAL_ID)).thenReturn(stored);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.switchCurrent(StorageSourceTestFixture.LOCAL_ID, StorageSourceTestFixture.OPERATOR));

        assertEquals(ErrorCode.STORAGE_SOURCE_ILLEGAL, e.getErrorCode());
        verify(fixture.storageSourceDbService, never()).clearCurrent();
        verify(fixture.storageSourceDbService, never()).markCurrent(any());
    }
}
