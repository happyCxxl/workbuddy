package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbStorageSourceDbService;
import com.workbuddy.common.domain.entity.WbStorageSource;
import com.workbuddy.common.dto.response.setting.StorageSourceVO;
import com.workbuddy.common.enums.storage.StorageSourceStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.infra.storage.StorageSourceDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存储数据源生命周期用例：空表 seed / 未停用行注册与当前启用标记 / 回落注册 /
 * 清单的连接参数与探测结论。
 *
 * <p>切换预览与切换用例在 {@link StorageSwitchServiceTest}；装配与夹具数据见
 * {@link StorageSourceTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class StorageSourceLifecycleTest {

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
     * 启动初始化：空表按配置 seed 两行并注册、标记当前行。
     *
     * <p>守护 {@code initialize}：seed 行缺主键或当前标记错即失败。
     */
    @Test
    void initializeShouldSeedTwoRowsWhenTableEmpty() {
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of());

        service.initialize();

        ArgumentCaptor<WbStorageSource> rows = ArgumentCaptor.forClass(WbStorageSource.class);
        verify(fixture.storageSourceDbService, times(2)).save(rows.capture());
        List<WbStorageSource> seeded = rows.getAllValues();
        Map<String, WbStorageSource> byName = new LinkedHashMap<>();
        seeded.forEach(item -> byName.put(item.getName(), item));
        assertEquals(2, byName.size());
        assertTrue(byName.containsKey("MinIO"), byName.keySet().toString());
        assertTrue(byName.containsKey("本地磁盘"), byName.keySet().toString());
        // 配置里的 storage-type = local：本地磁盘那一行置为当前启用
        assertEquals(WbStorageSourceDbService.CURRENT_YES, byName.get("本地磁盘").getIsCurrent());
        assertEquals(WbStorageSourceDbService.CURRENT_NO, byName.get("MinIO").getIsCurrent());
        assertEquals(StorageSourceStatus.ENABLED.getCode(), byName.get("MinIO").getStatus());
        assertNotNull(byName.get("本地磁盘").getConfigJson());
        // seed 行必须带主键：注册、当前标记与探活结论都按它定位
        assertNotNull(byName.get("MinIO").getId(), "seed 行缺主键");
        assertEquals(StorageType.LOCAL.getCode(), byName.get("本地磁盘").getStorageType());
        // seed 出来的两行都注册进路由，当前启用那一行做标记并回写这次探活的结论
        verify(fixture.storageRouter).markCurrent(byName.get("本地磁盘").getId());
        verify(fixture.storageRouter, times(2)).register(any(StorageSourceDef.class));
        verify(fixture.storageSourceDbService).recordProbe(eq(byName.get("本地磁盘").getId()), eq(true), any());
    }

    /**
     * 启动初始化：当前行探活失败先落库结论再抛出（fail-fast）。
     *
     * <p>守护 {@code initialize}：吞掉失败或结论不落库即失败。
     */
    @Test
    void initializeShouldRecordFailedProbeAndKeepFailingFast() {
        when(fixture.storageSourceDbService.listAll())
                .thenReturn(List.of(fixture.localRow(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", true)));
        doThrow(new KnowledgeException(ErrorCode.STORAGE_SOURCE_PROBE_FAILED, "连接不上"))
                .when(fixture.storageRouter).markCurrent(StorageSourceTestFixture.LOCAL_ID);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.initialize());

        assertEquals(ErrorCode.STORAGE_SOURCE_PROBE_FAILED, e.getErrorCode());
        // 失败结论先落库再抛：启动仍是 fail-fast，页面能看到这一行的连接状态
        verify(fixture.storageSourceDbService).recordProbe(eq(StorageSourceTestFixture.LOCAL_ID), eq(false), any());
    }

    /**
     * 启动初始化：只注册启用的数据源。
     *
     * <p>守护 {@code initialize}：停用行被注册即失败。
     */
    @Test
    void initializeShouldRegisterEnabledRowsOnly() {
        WbStorageSource enabled = fixture.localRow(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", true);
        WbStorageSource disabled = fixture.row(StorageSourceTestFixture.OTHER_ID, "归档 MinIO", StorageType.LOCAL,
                fixture.localParams(root.resolve("disabled").toString()), StorageSourceStatus.DISABLED, 0);
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of(enabled, disabled));

        service.initialize();

        ArgumentCaptor<StorageSourceDef> defs = ArgumentCaptor.forClass(StorageSourceDef.class);
        verify(fixture.storageRouter).register(defs.capture());
        assertEquals(StorageSourceTestFixture.LOCAL_ID, defs.getValue().id());
        verify(fixture.storageRouter).markCurrent(StorageSourceTestFixture.LOCAL_ID);
        verify(fixture.storageRouter, never()).markCurrent(StorageSourceTestFixture.OTHER_ID);
    }

    /**
     * 启动初始化：没有启用的行时按配置类型回落注册。
     *
     * <p>守护 {@code initialize}：无启用水源即失败。
     */
    @Test
    void initializeShouldFallBackToConfiguredTypeWithoutEnabledRow() {
        WbStorageSource disabled = fixture.row(StorageSourceTestFixture.OTHER_ID, "本地磁盘", StorageType.LOCAL,
                fixture.localParams(root.resolve("disabled").toString()), StorageSourceStatus.DISABLED, 0);
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of(disabled));

        service.initialize();

        ArgumentCaptor<StorageSourceDef> defs = ArgumentCaptor.forClass(StorageSourceDef.class);
        verify(fixture.storageRouter).register(defs.capture());
        assertEquals(StorageType.LOCAL, defs.getValue().type());
        assertEquals("本地磁盘", defs.getValue().name());
        verify(fixture.storageRouter).markCurrent(defs.getValue().id());
    }

    /**
     * 启动初始化：类型码值无法识别的行跳过且不当作当前启用。
     *
     * <p>守护 {@code initialize}：坏行被当作当前启用即失败。
     */
    @Test
    void initializeShouldSkipRowsWithUnrecognizedType() {
        WbStorageSource broken = fixture.row(StorageSourceTestFixture.OTHER_ID, "未知源", StorageType.LOCAL,
                fixture.localParams(root.resolve("broken").toString()), StorageSourceStatus.ENABLED, 0);
        broken.setStorageType("oss");
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of(broken));

        service.initialize();

        // 类型码值认不出来：该行不注册，也没有可用的当前启用，按配置回落
        ArgumentCaptor<StorageSourceDef> defs = ArgumentCaptor.forClass(StorageSourceDef.class);
        verify(fixture.storageRouter).register(defs.capture());
        assertEquals(StorageType.LOCAL, defs.getValue().type());
        verify(fixture.storageRouter).markCurrent(defs.getValue().id());
        // 认不出类型的那一行不能被当作当前启用
        verify(fixture.storageRouter, never()).markCurrent(StorageSourceTestFixture.OTHER_ID);
    }

    /**
     * 清单：当前/已注册/参数齐全标记与探测结论逐列下发。
     *
     * <p>守护 {@code list}：标记或参数缺列即失败。
     */
    @Test
    void listShouldMarkCurrentRegisteredAndReturnMinioParamsWithKeys() {
        WbStorageSource minio = fixture.row(StorageSourceTestFixture.LOCAL_ID, "演示 MinIO", StorageType.MINIO,
                fixture.minioParams(), StorageSourceStatus.ENABLED, 1);
        minio.setLastProbeOk(true);
        minio.setLastProbeAt(LocalDateTime.of(2026, 10, 9, 10, 30, 15, 123_000_000));
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of(minio));
        when(fixture.storageRouter.registered(StorageSourceTestFixture.LOCAL_ID)).thenReturn(true);

        List<StorageSourceVO> items = service.list();

        assertEquals(1, items.size());
        StorageSourceVO vo = items.getFirst();
        assertEquals(StorageSourceTestFixture.LOCAL_ID, vo.getId());
        assertEquals("演示 MinIO", vo.getName());
        assertEquals(StorageType.MINIO.getCode(), vo.getStorageType());
        assertEquals("MinIO", vo.getStorageTypeName());
        assertTrue(vo.isCurrent());
        assertTrue(vo.isRegistered());
        assertTrue(vo.isConfigured());
        // 最近一次探测结论随行下发，清单本身不做实时探测
        assertEquals(true, vo.getProbeOk());
        assertEquals("2026-10-09T10:30:15.123", vo.getProbeAt());
        // 连接参数齐全：minio 行的键集合含两个密钥键
        assertEquals(Set.of(StorageSourceDef.KEY_ENDPOINT, StorageSourceDef.KEY_ACCESS_KEY,
                StorageSourceDef.KEY_SECRET_KEY, StorageSourceDef.KEY_FILE_BUCKET,
                StorageSourceDef.KEY_ARTIFACT_BUCKET), vo.getParams().keySet());
        // 密钥键与值都随参数下发
        assertEquals("ak", vo.getParams().get(StorageSourceDef.KEY_ACCESS_KEY));
        assertEquals("sk", vo.getParams().get(StorageSourceDef.KEY_SECRET_KEY));
        assertEquals("http://127.0.0.1:1", vo.getParams().get(StorageSourceDef.KEY_ENDPOINT));
    }

    /**
     * 清单：本地磁盘行的参数键集合完整。
     *
     * <p>守护 {@code list}：参数缺键即失败。
     */
    @Test
    void listShouldReturnLocalParamsWithFullKeySet() {
        WbStorageSource local = fixture.row(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", StorageType.LOCAL,
                fixture.localParams(root.resolve("local-full").toString()), StorageSourceStatus.ENABLED, 0);
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of(local));

        StorageSourceVO vo = service.list().getFirst();

        // local 行没有密钥键，键集合就是三个目录参数
        assertEquals(Set.of(StorageSourceDef.KEY_ROOT_DIR, StorageSourceDef.KEY_FILE_DIR,
                StorageSourceDef.KEY_ARTIFACT_DIR), vo.getParams().keySet());
        assertEquals("files", vo.getParams().get(StorageSourceDef.KEY_FILE_DIR));
        assertEquals("artifacts", vo.getParams().get(StorageSourceDef.KEY_ARTIFACT_DIR));
    }

    /**
     * 清单：从未探测时探测列为空。
     *
     * <p>守护 {@code list}：伪造探测结论即失败。
     */
    @Test
    void listShouldReportUnprobedSourceWithNullProbeColumns() {
        WbStorageSource fresh = fixture.row(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", StorageType.LOCAL,
                fixture.localParams(root.resolve("fresh").toString()), StorageSourceStatus.ENABLED, 0);
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of(fresh));

        StorageSourceVO vo = service.list().getFirst();

        // 从未探测过：两列都为空，页面按「未探测」渲染
        assertNull(vo.getProbeOk());
        assertNull(vo.getProbeAt());
    }

    /**
     * 清单：探测失败如实下发。
     *
     * <p>守护 {@code list}：失败当成功即失败。
     */
    @Test
    void listShouldReportFailedProbe() {
        WbStorageSource broken = fixture.localRow(StorageSourceTestFixture.LOCAL_ID, "本地磁盘", false);
        broken.setLastProbeOk(false);
        broken.setLastProbeAt(LocalDateTime.of(2026, 10, 9, 11, 0));
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of(broken));

        StorageSourceVO vo = service.list().getFirst();

        assertEquals(false, vo.getProbeOk());
        assertEquals("2026-10-09T11:00", vo.getProbeAt());
    }

    /**
     * 清单：参数不全时按未配置下发。
     *
     * <p>守护 {@code list}：缺参数当齐全即失败。
     */
    @Test
    void listShouldReportIncompleteParams() {
        Map<String, String> incomplete = new LinkedHashMap<>();
        incomplete.put(StorageSourceDef.KEY_ENDPOINT, "http://127.0.0.1:1");
        WbStorageSource minio = fixture.row(StorageSourceTestFixture.LOCAL_ID, "演示 MinIO", StorageType.MINIO,
                incomplete, StorageSourceStatus.ENABLED, 0);
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of(minio));

        StorageSourceVO vo = service.list().getFirst();

        assertFalse(vo.isConfigured());
        assertFalse(vo.isCurrent());
    }

    /**
     * 清单：未注册的数据源不带当前标记。
     *
     * <p>守护 {@code list}：未注册也被标当前即失败。
     */
    @Test
    void listShouldReportUnregisteredSourceWithoutCurrentMark() {
        WbStorageSource row = fixture.row(StorageSourceTestFixture.LOCAL_ID, "未接入的 MinIO", StorageType.MINIO,
                fixture.minioParams(), StorageSourceStatus.DISABLED, 0);
        when(fixture.storageSourceDbService.listAll()).thenReturn(List.of(row));
        when(fixture.storageRouter.registered(StorageSourceTestFixture.LOCAL_ID)).thenReturn(false);

        StorageSourceVO vo = service.list().getFirst();

        // 停用行仍在清单里：registered=false，current=false，状态码值照原样下发
        assertFalse(vo.isRegistered());
        assertFalse(vo.isCurrent());
        assertEquals(StorageSourceStatus.DISABLED.getCode(), vo.getStatus());
    }
}
