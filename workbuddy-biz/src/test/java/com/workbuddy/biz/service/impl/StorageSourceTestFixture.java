package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbStorageSourceDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.biz.testkit.TransactionTestSupport;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbStorageSource;
import com.workbuddy.common.enums.storage.StorageSourceStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.infra.storage.StorageSourceDef;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 存储数据源测试夹具：装配被测服务（无真实事务 + 边界替身）、临时目录配置与常用行数据。
 *
 * <p>生命周期与切换两组用例共用；各测试类不再重复声明依赖。
 */
final class StorageSourceTestFixture {

    /** 本地磁盘数据源 ID */
    static final Long LOCAL_ID = 11L;

    /** 另一个数据源 ID */
    static final Long OTHER_ID = 22L;

    static final String OPERATOR = "admin";

    final WbStorageSourceDbService storageSourceDbService = mock(WbStorageSourceDbService.class);
    final WbPipelineTaskDbService pipelineTaskDbService = mock(WbPipelineTaskDbService.class);
    final WbAuditLogDbService kbAuditLogDbService = mock(WbAuditLogDbService.class);
    final StorageRouter storageRouter = mock(StorageRouter.class);
    final ChainStorageSupport chainStorageSupport = mock(ChainStorageSupport.class);

    private final Path root;

    private final StorageSourceServiceImpl service;

    StorageSourceTestFixture(Path root) {
        this.root = root;
        service = new StorageSourceServiceImpl(storageSourceDbService, pipelineTaskDbService,
                kbAuditLogDbService, storageRouter, TransactionTestSupport.template(),
                chainStorageSupport);
        lenient().when(chainStorageSupport.sourceName(any())).thenAnswer(inv -> {
            Object sourceId = inv.getArgument(0);
            return sourceId == null ? "未记录的数据源" : "数据源" + sourceId;
        });
    }

    /** 被测服务 */
    StorageSourceServiceImpl service() {
        return service;
    }

    /** 临时目录（测试类注入的 @TempDir） */
    Path root() {
        return root;
    }

    /** 本地磁盘连接参数 */
    Map<String, String> localParams(String rootDir) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(StorageSourceDef.KEY_ROOT_DIR, rootDir);
        params.put(StorageSourceDef.KEY_FILE_DIR, "files");
        params.put(StorageSourceDef.KEY_ARTIFACT_DIR, "artifacts");
        return params;
    }

    /** MinIO 连接参数（端口 1 无人监听，探测必失败且快） */
    Map<String, String> minioParams() {
        Map<String, String> params = new LinkedHashMap<>();
        // 端口 1 无人监听：连接测试必定失败，且失败得很快
        params.put(StorageSourceDef.KEY_ENDPOINT, "http://127.0.0.1:1");
        params.put(StorageSourceDef.KEY_ACCESS_KEY, "ak");
        params.put(StorageSourceDef.KEY_SECRET_KEY, "sk");
        params.put(StorageSourceDef.KEY_FILE_BUCKET, "files");
        params.put(StorageSourceDef.KEY_ARTIFACT_BUCKET, "artifacts");
        return params;
    }

    /** 数据源行夹具 */
    WbStorageSource row(Long id, String name, StorageType type, Map<String, String> params,
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

    /** 本地磁盘行夹具（启用，可选当前标记） */
    WbStorageSource localRow(Long id, String name, boolean current) {
        return row(id, name, StorageType.LOCAL, localParams(root.resolve("row-" + id).toString()),
                StorageSourceStatus.ENABLED, current ? 1 : 0);
    }

    /** 任务行夹具 */
    WbPipelineTask task(Long fileResultId, PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setFileResultId(fileResultId);
        task.setStatus(status.name());
        return task;
    }

    /** 活跃任务查询桩：按排队 + 运行状态返回指定任务 */
    void stubActiveTasks(WbPipelineTask... tasks) {
        when(pipelineTaskDbService.listByStatuses(List.of(PipelineTaskStatus.QUEUED,
                PipelineTaskStatus.RUNNING))).thenReturn(List.of(tasks));
    }
}
