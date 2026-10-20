package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.testkit.TransactionTestSupport;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbStorageSourceDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbStorageSource;
import com.workbuddy.common.dto.response.setting.StorageSourceVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchPreviewVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchResultVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.enums.storage.StorageSourceStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.config.FileCenterConfig;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.infra.storage.StorageSourceDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存储数据源服务单测：空表 seed / 未停用行注册与当前启用标记 / 回落注册 /
 * 清单的连接参数与探测结论 / 切换预览不落库与目标的可用性校验 /
 * 切换的先清后置、探测回写、注册与审计。
 */
@ExtendWith(MockitoExtension.class)
class StorageSourceServiceImplTest {

    /** 本地磁盘数据源 ID */
    private static final Long LOCAL_ID = 11L;

    /** 另一个数据源 ID */
    private static final Long OTHER_ID = 22L;

    private static final String OPERATOR = "admin";

    @TempDir
    Path root;

    @Mock
    private WbStorageSourceDbService storageSourceDbService;
    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbAuditLogDbService kbAuditLogDbService;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private ChainStorageSupport chainStorageSupport;

    private FileCenterConfig fileCenterConfig;

    private StorageSourceServiceImpl service;

    @BeforeEach
    void setUp() {
        fileCenterConfig = new FileCenterConfig();
        fileCenterConfig.setStorageType("local");
        fileCenterConfig.getLocal().setRootDir(root.resolve("seeded-local").toString());
        service = new StorageSourceServiceImpl(storageSourceDbService, pipelineTaskDbService,
                kbAuditLogDbService, storageRouter, TransactionTestSupport.template(),
                chainStorageSupport, fileCenterConfig);
        lenient().when(chainStorageSupport.sourceName(any())).thenAnswer(inv -> {
            Object sourceId = inv.getArgument(0);
            return sourceId == null ? "未记录的数据源" : "数据源" + sourceId;
        });
    }

    // ---------------- 夹具 ----------------

    private Map<String, String> localParams(String rootDir) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(StorageSourceDef.KEY_ROOT_DIR, rootDir);
        params.put(StorageSourceDef.KEY_FILE_DIR, "files");
        params.put(StorageSourceDef.KEY_ARTIFACT_DIR, "artifacts");
        return params;
    }

    private Map<String, String> minioParams() {
        Map<String, String> params = new LinkedHashMap<>();
        // 端口 1 无人监听：连接测试必定失败，且失败得很快
        params.put(StorageSourceDef.KEY_ENDPOINT, "http://127.0.0.1:1");
        params.put(StorageSourceDef.KEY_ACCESS_KEY, "ak");
        params.put(StorageSourceDef.KEY_SECRET_KEY, "sk");
        params.put(StorageSourceDef.KEY_FILE_BUCKET, "files");
        params.put(StorageSourceDef.KEY_ARTIFACT_BUCKET, "artifacts");
        return params;
    }

    private WbStorageSource row(Long id, String name, StorageType type, Map<String, String> params,
                                StorageSourceStatus status, int isCurrent) {
        WbStorageSource row = new WbStorageSource();
        row.setId(id);
        row.setName(name);
        row.setStorageType(type.getCode());
        row.setConfigJson(JsonUtil.toJsonStr(params));
        row.setStatus(status.getCode());
        row.setIsCurrent(isCurrent);
        return row;
    }

    private WbStorageSource localRow(Long id, String name, boolean current) {
        return row(id, name, StorageType.LOCAL, localParams(root.resolve("row-" + id).toString()),
                StorageSourceStatus.ENABLED, current ? 1 : 0);
    }

    private WbPipelineTask task(Long fileResultId, PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setFileResultId(fileResultId);
        task.setStatus(status.name());
        return task;
    }

    // ---------------- 启动初始化 ----------------

    /**
     * 启动初始化：空表按配置 seed 两行并注册、标记当前行。
     *
     * <p>守护 {@code initialize}：seed 行缺主键或当前标记错即失败。
     */
    @Test
    void initializeShouldSeedTwoRowsWhenTableEmpty() {
        when(storageSourceDbService.listAll()).thenReturn(List.of());

        service.initialize();

        ArgumentCaptor<WbStorageSource> rows = ArgumentCaptor.forClass(WbStorageSource.class);
        verify(storageSourceDbService, times(2)).save(rows.capture());
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
        verify(storageRouter).markCurrent(byName.get("本地磁盘").getId());
        verify(storageRouter, times(2)).register(any(StorageSourceDef.class));
        verify(storageSourceDbService).recordProbe(eq(byName.get("本地磁盘").getId()), eq(true), any());
    }

    /**
     * 启动初始化：当前行探活失败先落库结论再抛出（fail-fast）。
     *
     * <p>守护 {@code initialize}：吞掉失败或结论不落库即失败。
     */
    @Test
    void initializeShouldRecordFailedProbeAndKeepFailingFast() {
        when(storageSourceDbService.listAll()).thenReturn(List.of(localRow(LOCAL_ID, "本地磁盘", true)));
        doThrow(new KnowledgeException(ErrorCode.STORAGE_SOURCE_PROBE_FAILED, "连接不上"))
                .when(storageRouter).markCurrent(LOCAL_ID);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.initialize());

        assertEquals(ErrorCode.STORAGE_SOURCE_PROBE_FAILED, e.getErrorCode());
        // 失败结论先落库再抛：启动仍是 fail-fast，页面能看到这一行的连接状态
        verify(storageSourceDbService).recordProbe(eq(LOCAL_ID), eq(false), any());
    }

    /**
     * 启动初始化：只注册启用的数据源。
     *
     * <p>守护 {@code initialize}：停用行被注册即失败。
     */
    @Test
    void initializeShouldRegisterEnabledRowsOnly() {
        WbStorageSource enabled = localRow(LOCAL_ID, "本地磁盘", true);
        WbStorageSource disabled = row(OTHER_ID, "归档 MinIO", StorageType.LOCAL,
                localParams(root.resolve("disabled").toString()), StorageSourceStatus.DISABLED, 0);
        when(storageSourceDbService.listAll()).thenReturn(List.of(enabled, disabled));

        service.initialize();

        ArgumentCaptor<StorageSourceDef> defs = ArgumentCaptor.forClass(StorageSourceDef.class);
        verify(storageRouter).register(defs.capture());
        assertEquals(LOCAL_ID, defs.getValue().id());
        verify(storageRouter).markCurrent(LOCAL_ID);
        verify(storageRouter, never()).markCurrent(OTHER_ID);
    }

    /**
     * 启动初始化：没有启用的行时按配置类型回落注册。
     *
     * <p>守护 {@code initialize}：无启用水源即失败。
     */
    @Test
    void initializeShouldFallBackToConfiguredTypeWithoutEnabledRow() {
        WbStorageSource disabled = row(OTHER_ID, "本地磁盘", StorageType.LOCAL,
                localParams(root.resolve("disabled").toString()), StorageSourceStatus.DISABLED, 0);
        when(storageSourceDbService.listAll()).thenReturn(List.of(disabled));

        service.initialize();

        ArgumentCaptor<StorageSourceDef> defs = ArgumentCaptor.forClass(StorageSourceDef.class);
        verify(storageRouter).register(defs.capture());
        assertEquals(StorageType.LOCAL, defs.getValue().type());
        assertEquals("本地磁盘", defs.getValue().name());
        verify(storageRouter).markCurrent(defs.getValue().id());
    }

    /**
     * 启动初始化：类型码值无法识别的行跳过且不当作当前启用。
     *
     * <p>守护 {@code initialize}：坏行被当作当前启用即失败。
     */
    @Test
    void initializeShouldSkipRowsWithUnrecognizedType() {
        WbStorageSource broken = row(OTHER_ID, "未知源", StorageType.LOCAL,
                localParams(root.resolve("broken").toString()), StorageSourceStatus.ENABLED, 0);
        broken.setStorageType("oss");
        when(storageSourceDbService.listAll()).thenReturn(List.of(broken));

        service.initialize();

        // 类型码值认不出来：该行不注册，也没有可用的当前启用，按配置回落
        ArgumentCaptor<StorageSourceDef> defs = ArgumentCaptor.forClass(StorageSourceDef.class);
        verify(storageRouter).register(defs.capture());
        assertEquals(StorageType.LOCAL, defs.getValue().type());
        verify(storageRouter).markCurrent(defs.getValue().id());
        // 认不出类型的那一行不能被当作当前启用
        verify(storageRouter, never()).markCurrent(OTHER_ID);
    }

    /**
     * 切换目标类型码值无法识别：按 40054 拒绝，不注册也不改库。
     *
     * <p>守护 {@code defOf} 的类型兜底：拿着 null 类型去建存储会把配置错误报成连接失败。
     */
    @Test
    void switchShouldRejectUnrecognizedTargetType() {
        WbStorageSource broken = localRow(LOCAL_ID, "未知源", false);
        broken.setStorageType("oss");
        when(storageSourceDbService.getById(LOCAL_ID)).thenReturn(broken);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.switchCurrent(LOCAL_ID, OPERATOR));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        verify(storageRouter, never()).register(any(StorageSourceDef.class));
        verify(storageSourceDbService, never()).clearCurrent();
    }

    // ---------------- 清单与参数 ----------------

    /**
     * 清单：当前/已注册/参数齐全标记与探测结论逐列下发。
     *
     * <p>守护 {@code list}：标记或参数缺列即失败。
     */
    @Test
    void listShouldMarkCurrentRegisteredAndReturnMinioParamsWithKeys() {
        WbStorageSource minio = row(LOCAL_ID, "演示 MinIO", StorageType.MINIO, minioParams(),
                StorageSourceStatus.ENABLED, 1);
        minio.setLastProbeOk(true);
        minio.setLastProbeAt(LocalDateTime.of(2026, 10, 9, 10, 30, 15, 123_000_000));
        when(storageSourceDbService.listAll()).thenReturn(List.of(minio));
        when(storageRouter.registered(LOCAL_ID)).thenReturn(true);

        List<StorageSourceVO> items = service.list();

        assertEquals(1, items.size());
        StorageSourceVO vo = items.getFirst();
        assertEquals(LOCAL_ID, vo.getId());
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
        WbStorageSource local = row(LOCAL_ID, "本地磁盘", StorageType.LOCAL,
                localParams(root.resolve("local-full").toString()), StorageSourceStatus.ENABLED, 0);
        when(storageSourceDbService.listAll()).thenReturn(List.of(local));

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
        WbStorageSource fresh = row(LOCAL_ID, "本地磁盘", StorageType.LOCAL,
                localParams(root.resolve("fresh").toString()), StorageSourceStatus.ENABLED, 0);
        when(storageSourceDbService.listAll()).thenReturn(List.of(fresh));

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
        WbStorageSource broken = localRow(LOCAL_ID, "本地磁盘", false);
        broken.setLastProbeOk(false);
        broken.setLastProbeAt(LocalDateTime.of(2026, 10, 9, 11, 0));
        when(storageSourceDbService.listAll()).thenReturn(List.of(broken));

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
        WbStorageSource minio = row(LOCAL_ID, "演示 MinIO", StorageType.MINIO, incomplete,
                StorageSourceStatus.ENABLED, 0);
        when(storageSourceDbService.listAll()).thenReturn(List.of(minio));

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
        WbStorageSource row = row(LOCAL_ID, "未接入的 MinIO", StorageType.MINIO, minioParams(),
                StorageSourceStatus.DISABLED, 0);
        when(storageSourceDbService.listAll()).thenReturn(List.of(row));
        when(storageRouter.registered(LOCAL_ID)).thenReturn(false);

        StorageSourceVO vo = service.list().getFirst();

        // 停用行仍在清单里：registered=false，current=false，状态码值照原样下发
        assertFalse(vo.isRegistered());
        assertFalse(vo.isCurrent());
        assertEquals(StorageSourceStatus.DISABLED.getCode(), vo.getStatus());
    }

    // ---------------- 切换预览 ----------------

    /**
     * 切换预览：影响面计数与两侧数据源信息逐列下发，且不落库。
     *
     * <p>守护 {@code previewCurrent}：漏字段或偷偷落库即失败。
     */
    @Test
    void previewShouldCountImpactWithoutPersisting() {
        when(storageSourceDbService.getById(LOCAL_ID)).thenReturn(localRow(LOCAL_ID, "本地磁盘", false));
        when(storageRouter.currentSourceId()).thenReturn(OTHER_ID);
        when(pipelineTaskDbService.listByStatuses(List.of(PipelineTaskStatus.QUEUED,
                PipelineTaskStatus.RUNNING)))
                .thenReturn(List.of(task(10L, PipelineTaskStatus.QUEUED), task(11L, PipelineTaskStatus.RUNNING),
                        task(12L, PipelineTaskStatus.QUEUED)));
        when(chainStorageSupport.chainSourceIdsOf(List.of(10L, 11L, 12L)))
                .thenReturn(Map.of(10L, OTHER_ID, 11L, OTHER_ID, 12L, LOCAL_ID));
        when(chainStorageSupport.sourceName(OTHER_ID)).thenReturn("旧数据源");
        when(storageRouter.typeOf(OTHER_ID)).thenReturn(StorageType.MINIO);

        StorageSwitchPreviewVO vo = service.previewCurrent(LOCAL_ID);

        assertEquals(LOCAL_ID, vo.getTargetSourceId());
        assertEquals("本地磁盘", vo.getTargetName());
        assertEquals(StorageType.LOCAL.getCode(), vo.getTargetType());
        assertEquals(OTHER_ID, vo.getCurrentSourceId());
        assertEquals("旧数据源", vo.getCurrentName());
        assertEquals(StorageType.MINIO.getCode(), vo.getCurrentType());
        assertEquals(1L, vo.getQueuedCount());
        assertEquals(1L, vo.getRunningCount());
        assertTrue(vo.getMessage().contains("旧数据源"), vo.getMessage());
        assertTrue(vo.getMessage().contains("1 个排队中"), vo.getMessage());
        // 预览不落库、不切换、不落审计
        verify(storageSourceDbService, never()).clearCurrent();
        verify(storageSourceDbService, never()).markCurrent(any());
        verify(storageSourceDbService, never()).updateById(any());
        verify(storageRouter, never()).markCurrent(any());
        verify(kbAuditLogDbService, never()).saveAudit(any(), anyString(), any(), any(), any());
    }

    /**
     * 切换预览：目标已停用按 40458 拒绝。
     *
     * <p>守护 {@code previewCurrent}：停用目标可预览即失败。
     */
    @Test
    void previewShouldRejectDisabledTarget() {
        WbStorageSource stored = localRow(LOCAL_ID, "本地磁盘", false);
        stored.setStatus(StorageSourceStatus.DISABLED.getCode());
        when(storageSourceDbService.getById(LOCAL_ID)).thenReturn(stored);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.previewCurrent(LOCAL_ID));

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
        when(storageSourceDbService.getById(LOCAL_ID)).thenReturn(row(LOCAL_ID, "本地磁盘", StorageType.LOCAL,
                incomplete, StorageSourceStatus.ENABLED, 0));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.previewCurrent(LOCAL_ID));

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
        when(storageSourceDbService.getById(OTHER_ID)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.previewCurrent(OTHER_ID));

        assertEquals(ErrorCode.STORAGE_SOURCE_NOT_FOUND, e.getErrorCode());
    }

    // ---------------- 设为当前启用 ----------------

    /**
     * 切换：注册、探活、清旧置新、结论与审计落库，结果行字段齐全。
     *
     * <p>守护 {@code switchCurrent}：漏字段或漏审计即失败。
     */
    @Test
    void switchShouldPersistRegisterAndAudit() {
        when(storageSourceDbService.getById(LOCAL_ID)).thenReturn(localRow(LOCAL_ID, "本地磁盘", false));
        when(storageRouter.currentSourceId()).thenReturn(OTHER_ID);
        when(pipelineTaskDbService.listByStatuses(List.of(PipelineTaskStatus.QUEUED,
                PipelineTaskStatus.RUNNING)))
                .thenReturn(List.of(task(10L, PipelineTaskStatus.QUEUED)));
        when(chainStorageSupport.chainSourceIdsOf(List.of(10L)))
                .thenReturn(Map.of(10L, OTHER_ID));
        when(chainStorageSupport.sourceName(OTHER_ID)).thenReturn("旧数据源");

        StorageSwitchResultVO vo = service.switchCurrent(LOCAL_ID, OPERATOR);

        assertEquals(LOCAL_ID, vo.getSwitchedSourceId());
        assertEquals(LOCAL_ID, vo.getCurrentSourceId());
        assertEquals("本地磁盘", vo.getCurrentName());
        assertEquals(StorageType.LOCAL.getCode(), vo.getCurrentType());
        assertEquals(LOCAL_ID, vo.getTargetSourceId());
        assertEquals("本地磁盘", vo.getTargetName());
        assertEquals(1L, vo.getQueuedCount());
        assertEquals(0L, vo.getRunningCount());
        assertTrue(vo.getMessage().contains("已切换到 本地磁盘"), vo.getMessage());
        assertTrue(vo.getMessage().contains("旧数据源"), vo.getMessage());
        verify(storageRouter).register(any(StorageSourceDef.class));
        // 先清后置，全表至多一行为 1；探活结论随这次切换一起落库
        InOrder order = inOrder(storageSourceDbService);
        order.verify(storageSourceDbService).clearCurrent();
        order.verify(storageSourceDbService).markCurrent(LOCAL_ID);
        // 注册与探活先于 DB 事务；进程内 markCurrent 在 DB 提交后
        InOrder externalFirst = inOrder(storageRouter, storageSourceDbService);
        externalFirst.verify(storageRouter).register(any(StorageSourceDef.class));
        externalFirst.verify(storageSourceDbService).clearCurrent();
        externalFirst.verify(storageRouter).markCurrent(LOCAL_ID);
        verify(storageSourceDbService).recordProbe(eq(LOCAL_ID), eq(true), any());
        verify(storageRouter).markCurrent(LOCAL_ID);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(kbAuditLogDbService).saveAudit(eq(AuditActionType.STORAGE_SWITCH),
                eq(AuditObjectType.STORAGE_SOURCE.key()), eq(LOCAL_ID), anyString(), after.capture());
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
        when(storageSourceDbService.getById(LOCAL_ID)).thenReturn(localRow(LOCAL_ID, "本地磁盘", true));
        when(storageRouter.currentSourceId()).thenReturn(LOCAL_ID);

        StorageSwitchResultVO vo = service.switchCurrent(LOCAL_ID, OPERATOR);

        assertEquals(LOCAL_ID, vo.getSwitchedSourceId());
        assertTrue(vo.getMessage().contains("已经是"), vo.getMessage());
        verify(storageSourceDbService, never()).clearCurrent();
        verify(storageSourceDbService, never()).markCurrent(any());
        verify(storageSourceDbService, never()).recordProbe(any(), anyBoolean(), any());
        verify(storageRouter, never()).markCurrent(any());
    }

    /**
     * 切换：探活失败整体不落库（结论与审计都不写）。
     *
     * <p>守护 {@code switchCurrent}：失败留半截状态即失败。
     */
    @Test
    void switchShouldNotPersistWhenProbeFails() {
        WbStorageSource stored = row(LOCAL_ID, "演示 MinIO", StorageType.MINIO, minioParams(),
                StorageSourceStatus.ENABLED, 0);
        when(storageSourceDbService.getById(LOCAL_ID)).thenReturn(stored);
        when(storageRouter.currentSourceId()).thenReturn(OTHER_ID);
        when(pipelineTaskDbService.listByStatuses(any())).thenReturn(List.of());

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.switchCurrent(LOCAL_ID, OPERATOR));

        assertEquals(ErrorCode.STORAGE_SOURCE_PROBE_FAILED, e.getErrorCode());
        verify(storageSourceDbService, never()).clearCurrent();
        verify(storageSourceDbService, never()).markCurrent(any());
        // 探活失败整体回滚：探测结论也不落库，不留半截状态
        verify(storageSourceDbService, never()).recordProbe(any(), anyBoolean(), any());
        verify(kbAuditLogDbService, never()).saveAudit(any(), anyString(), any(), any(), any());
    }

    /**
     * 切换：目标已停用按 40458 拒绝且不写库。
     *
     * <p>守护 {@code switchCurrent}：停用目标可切换即失败。
     */
    @Test
    void switchShouldRejectDisabledTargetWith40458() {
        WbStorageSource stored = localRow(LOCAL_ID, "本地磁盘", false);
        stored.setStatus(StorageSourceStatus.DISABLED.getCode());
        when(storageSourceDbService.getById(LOCAL_ID)).thenReturn(stored);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.switchCurrent(LOCAL_ID, OPERATOR));

        assertEquals(ErrorCode.STORAGE_SOURCE_ILLEGAL, e.getErrorCode());
        verify(storageSourceDbService, never()).clearCurrent();
        verify(storageSourceDbService, never()).markCurrent(any());
    }
}
