package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.comboAll;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.comboList;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.file;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.indexRow;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.indexSet;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.kbOff;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.kbOn;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.listVersion;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.versionRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物就绪回调单测：绑定开关下的注册/追加、自动发布判定、冻结集路由、失败回写与版本号并发兜底。
 */
class IndexBuildCallbackTest {

    private IndexBuildFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new IndexBuildFixture();
    }

    /**
     * 产物就绪（绑定开）：登记组合、追加、对账并自动发布。
     *
     * <p>守护 {@code onFileProductsReady}：漏追加或漏发布即失败。
     */
    @Test
    void onFileProductsReadyWhenBindingOnShouldAppendAndAutoPublish() {
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOn());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        // 注册组合
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        AtomicReference<WbIndexVersion> savedVersion = new AtomicReference<>();
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            v.setId(100L);
            savedVersion.set(v);
            return true;
        });
        // 对账
        when(fixture.milvusIndexPort.listChunkIds("kb_1_v1", 10L)).thenReturn(List.of("chunk-1"));
        // 发布：绑定组合 == 产物组合
        when(fixture.indexComboService.resolveBoundCombo(1L)).thenReturn(comboAll());
        // 规则三 / 冻结集路由：无在线版本、无其他版本
        when(fixture.publishService.currentPublished(1L)).thenReturn(null);
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(indexSet());

        fixture.service.onFileProductsReady(10L);

        verify(fixture.milvusIndexPort).ensureCollection("kb_1_v1", 1024);
        verify(fixture.milvusIndexPort).load("kb_1_v1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.workbuddy.worker.indexing.IndexRow>> rowsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.milvusIndexPort).append(eq("kb_1_v1"), rowsCaptor.capture());
        assertEquals(1, rowsCaptor.getValue().size());
        assertEquals("chunk-1", rowsCaptor.getValue().getFirst().getChunkId());
        // 活账本 + 自动发布委托
        assertEquals(1, savedVersion.get().getChunkCount());
        assertEquals(1, savedVersion.get().getVectorCount());
        assertEquals(IndexVersionStatus.READY.name(), savedVersion.get().getStatus());
        // 追加后转 READY 时必须记下校验时间（前端「最近校验」依赖它）
        assertNotNull(savedVersion.get().getValidatedAt());
        verify(fixture.publishService).publish(100L);
    }

    /**
     * 产物就绪（绑定关）：评测模式不自动登记组合。
     *
     * <p>守护 {@code onFileProductsReady}：测评库被自动建集合即失败。
     */
    @Test
    void onFileProductsReadyWhenBindingOffShouldSkipAutoRegister() {
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOff());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.publishService.currentPublished(1L)).thenReturn(null);
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(indexSet());

        fixture.service.onFileProductsReady(10L);

        // 评测模式（绑定关）：不自动注册组合、不建集合、不追加
        verify(fixture.indexVersionDbService, never()).save(any(WbIndexVersion.class));
        verify(fixture.milvusIndexPort, never()).ensureCollection(anyString(), anyInt());
        verify(fixture.milvusIndexPort, never()).load(anyString());
        verify(fixture.milvusIndexPort, never()).append(anyString(), anyList());
        assertTrue(mockingDetails(fixture.indexVersionDbService).getInvocations().stream()
                .noneMatch(inv -> "save".equals(inv.getMethod().getName())));
    }

    /**
     * 产物就绪（绑定关）：已有活跃版本时照常追加。
     *
     * <p>守护 {@code onFileProductsReady}：有集合不追加即失败。
     */
    @Test
    void onFileProductsReadyWhenBindingOffShouldAppendToExistingActiveVersion() {
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOff());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        // 手动构建已产出的同组合 READY 版本：回调仍应追加（评测模式只关"自动注册"，不关追加）
        WbIndexVersion ready = versionRow(90L, "v1", IndexVersionStatus.READY.name());
        ready.setChunkCount(0);
        ready.setVectorCount(0);
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(ready));
        when(fixture.milvusIndexPort.listChunkIds("kb_1_v1", 10L)).thenReturn(List.of("chunk-1"));
        when(fixture.publishService.currentPublished(1L)).thenReturn(null);
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(indexSet());

        fixture.service.onFileProductsReady(10L);

        verify(fixture.milvusIndexPort).append(eq("kb_1_v1"), anyList());
        verify(fixture.indexVersionDbService, never()).save(any(WbIndexVersion.class));
        verify(fixture.milvusIndexPort, never()).ensureCollection(anyString(), anyInt());
        assertEquals(1, ready.getChunkCount());
    }

    /**
     * 产物就绪：在线版本快照缺环节维度按 40448 拒绝。
     *
     * <p>守护 {@code onFileProductsReady}：静默跳过或空指针即失败。
     */
    @Test
    void onFileProductsReadyWhenOnlineComboIsLegacyShouldFailWithExplicitError() {
        // 在线版本为缺少 stageStrategies 的快照 → 快照边界断言拒绝（40448），不 NPE、不静默跳过
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOn());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v2");
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(100L);
            return true;
        });
        when(fixture.milvusIndexPort.listChunkIds("kb_1_v2", 10L)).thenReturn(List.of("chunk-1"));
        WbIndexVersion onlineVersion = new WbIndexVersion();
        onlineVersion.setId(50L);
        onlineVersion.setVersionNo("v1");
        onlineVersion.setComboSnapshot("{\"fileScopeMode\":\"ALL\",\"shape\":\"FULL_VECTOR\"}");
        when(fixture.publishService.currentPublished(1L)).thenReturn(onlineVersion);
        when(fixture.indexComboService.resolveBoundCombo(1L)).thenReturn(comboAll());
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(indexSet());

        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> fixture.service.onFileProductsReady(10L));

        assertEquals(ErrorCode.INDEX_COMBO_SNAPSHOT_LEGACY.getCode(), ex.getCode());
        // 本文件已按产物组合追加（幂等，重灌后可重放），失败点恰在拓扑重放
        verify(fixture.milvusIndexPort).append(eq("kb_1_v2"), anyList());
        verify(fixture.preprocessControlService, never()).preprocess(any(), any(), any());
    }

    /**
     * 产物就绪：只追加到范围包含该文件的冻结集版本。
     *
     * <p>守护 {@code onFileProductsReady}：范围外文件混进冻结集即失败。
     */
    @Test
    void onFileProductsReadyShouldAppendToMatchingFrozenScopesOnly() {
        // 评测模式（绑定关）不自动注册 ALL 组合；范围内三元组匹配的 LIST 版本照常追加
        //（范围外/三元组不同不进）
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOff());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(indexSet());
        // 三个 LIST 版本：v9 范围内+三元组匹配；v8 范围不含该文件；v7 三元组不同
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(
                listVersion("v9", IndexVersionStatus.READY.name(), comboList(List.of(10L), "embed-default-v1")),
                listVersion("v8", IndexVersionStatus.READY.name(), comboList(List.of(11L), "embed-default-v1")),
                listVersion("v7", IndexVersionStatus.READY.name(), comboList(List.of(10L), "embed-other-v2"))));
        when(fixture.publishService.currentPublished(1L)).thenReturn(null);

        fixture.service.onFileProductsReady(10L);

        // 评测模式：不注册 ALL 组合（无 kb_1_v1），仅冻结集追加
        verify(fixture.indexVersionDbService, never()).save(any(WbIndexVersion.class));
        verify(fixture.milvusIndexPort, never()).ensureCollection(anyString(), anyInt());
        verify(fixture.milvusIndexPort, never()).append(eq("kb_1_v1"), anyList());
        verify(fixture.milvusIndexPort).append(eq("kb_1_v9"), anyList());
        verify(fixture.milvusIndexPort, never()).append(eq("kb_1_v8"), anyList());
        verify(fixture.milvusIndexPort, never()).append(eq("kb_1_v7"), anyList());
    }

    /**
     * 集合初始化失败：版本行落 FAILED 并带上截断后的错误原因。
     *
     * <p>守护 {@code markFailed}：不置 FAILED 会让版本永远停在 CREATED、对账也看不出原因。
     */
    @Test
    void registerCollectionFailureShouldMarkVersionFailed() {
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOn());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        AtomicReference<WbIndexVersion> saved = new AtomicReference<>();
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            v.setId(100L);
            saved.set(v);
            return true;
        });
        doThrow(new IllegalStateException("milvus 不可用"))
                .when(fixture.milvusIndexPort).ensureCollection("kb_1_v1", 1024);
        when(fixture.publishService.currentPublished(1L)).thenReturn(null);
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(null);

        fixture.service.onFileProductsReady(10L);

        assertEquals(IndexVersionStatus.FAILED.name(), saved.get().getStatus());
        assertTrue(saved.get().getBuildError().contains("集合初始化失败"), saved.get().getBuildError());
        verify(fixture.indexVersionDbService).updateById(saved.get());
    }

    /**
     * 向量产物读取失败（装配返回 null）：版本行落 FAILED，不再追加。
     *
     * <p>守护 {@code onFileProductsReady} 的空产物兜底：静默跳过会让版本永远缺这批文件。
     */
    @Test
    void onFileProductsReadyWhenRowsUnavailableShouldMarkFailed() {
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOn());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(fixture.indexRowAssembler.assemble(eq(10L), anyString(), any(), any())).thenReturn(null);
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        AtomicReference<WbIndexVersion> saved = new AtomicReference<>();
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenAnswer(inv -> saved.get() == null
                ? List.of() : List.of(saved.get()));
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            v.setId(100L);
            v.setStatus(IndexVersionStatus.CREATED.name());
            saved.set(v);
            return true;
        });

        fixture.service.onFileProductsReady(10L);

        assertEquals(IndexVersionStatus.FAILED.name(), saved.get().getStatus());
        assertTrue(saved.get().getBuildError().contains("向量产物读取失败"), saved.get().getBuildError());
        // 新版本账本从 0 起（不是 null）：前端按数字格式化
        assertEquals(0, saved.get().getChunkCount());
        assertEquals(0, saved.get().getVectorCount());
        verify(fixture.milvusIndexPort, never()).append(anyString(), anyList());
    }

    /**
     * 组合注册撞版本号唯一键：重取版本号再存一次。
     *
     * <p>守护 {@code registerComboCollection} 的并发兜底：不重取版本号会第二次同样撞键、注册失败。
     */
    @Test
    void registerShouldRetryVersionNoOnDuplicateKey() {
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOn());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1", "v2");
        // 第一次保存撞 uk_set_version，重取版本号后成功
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_set_version"))
                .thenAnswer(inv -> {
                    inv.<WbIndexVersion>getArgument(0).setId(100L);
                    return true;
                });
        when(fixture.publishService.currentPublished(1L)).thenReturn(null);
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(null);

        fixture.service.onFileProductsReady(10L);

        ArgumentCaptor<WbIndexVersion> captor = ArgumentCaptor.forClass(WbIndexVersion.class);
        verify(fixture.indexVersionDbService, org.mockito.Mockito.times(2)).save(captor.capture());
        // 同一版本行对象：重试前被改写成新版本号，最终落库与建集合都用 v2
        assertEquals("v2", captor.getValue().getVersionNo());
        verify(fixture.milvusIndexPort).ensureCollection("kb_1_v2", 1024);
    }

    /**
     * 追加只统计带向量的行，并清掉历史失败原因。
     *
     * <p>守护 {@code updateLedger}：把无向量行也计入向量数会虚报；留着旧 buildError 会一直显示失败。
     */
    @Test
    void appendShouldCountOnlyVectorRowsAndClearBuildError() {
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOn());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        WbIndexVersion active = versionRow(100L, "v1", IndexVersionStatus.READY.name());
        active.setChunkCount(4);
        active.setVectorCount(3);
        active.setBuildError("上次失败");
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(active));
        com.workbuddy.worker.indexing.IndexRow withVector = indexRow();
        com.workbuddy.worker.indexing.IndexRow withoutVector = indexRow();
        withoutVector.setChunkId("chunk-2");
        withoutVector.setVector(null);
        when(fixture.indexRowAssembler.assemble(eq(10L), anyString(), any(), any()))
                .thenReturn(List.of(withVector, withoutVector));
        when(fixture.milvusIndexPort.listChunkIds("kb_1_v1", 10L)).thenReturn(List.of("chunk-1", "chunk-2"));
        when(fixture.indexComboService.resolveBoundCombo(1L)).thenReturn(comboAll());
        when(fixture.publishService.currentPublished(1L)).thenReturn(null);
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(null);

        fixture.service.onFileProductsReady(10L);

        assertEquals(6, active.getChunkCount());
        assertEquals(4, active.getVectorCount());
        assertNull(active.getBuildError());
        verify(fixture.indexVersionDbService).updateById(active);
    }

    /**
     * 活跃版本只认未终结状态：同组合的已退役版本不算活跃，绑定开启时照常登记新组合版本。
     *
     * <p>守护 {@code findActiveVersion} 的状态过滤：把退役版本当活跃会让新组合永远登记不上。
     */
    @Test
    void registerShouldIgnoreRetiredVersionWithSameCombo() {
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(kbOn());
        fixture.stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        WbIndexVersion retired = versionRow(90L, "v1", IndexVersionStatus.RETIRED.name());
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(retired));
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v2");
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(102L);
            return true;
        });
        when(fixture.publishService.currentPublished(1L)).thenReturn(null);
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(null);

        fixture.service.onFileProductsReady(10L);

        ArgumentCaptor<WbIndexVersion> versionCaptor = ArgumentCaptor.forClass(WbIndexVersion.class);
        verify(fixture.indexVersionDbService).save(versionCaptor.capture());
        assertEquals("v2", versionCaptor.getValue().getVersionNo());
        assertEquals(IndexVersionStatus.READY.name(), versionCaptor.getValue().getStatus());
        verify(fixture.milvusIndexPort).ensureCollection("kb_1_v2", 1024);
    }
}
