package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.IndexBuildService;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.support.index.IndexVersionStateSupport;
import com.workbuddy.biz.testkit.TransactionTestSupport;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import com.workbuddy.common.convert.knowledge.PipelineConverterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;

import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.comboList;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.indexSet;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.versionRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回退与回收单测：指针切回 + 审计 + 同事务驱动构建补齐、回收的在线/构建中/退役护栏与事务外 drop。
 */
class IndexVersionServiceImplTest {

    private WbIndexSetDbService indexSetDbService;
    private WbIndexVersionDbService indexVersionDbService;
    private WbAuditLogDbService kbAuditLogDbService;
    private MilvusIndexPort milvusIndexPort;
    private IndexBuildService buildService;
    private IndexVersionServiceImpl service;

    @BeforeEach
    void setUp() {
        indexSetDbService = mock(WbIndexSetDbService.class);
        indexVersionDbService = mock(WbIndexVersionDbService.class);
        kbAuditLogDbService = mock(WbAuditLogDbService.class);
        milvusIndexPort = mock(MilvusIndexPort.class);
        buildService = mock(IndexBuildService.class);
        IndexVersionStateSupport stateSupport =
                new IndexVersionStateSupport(indexSetDbService, indexVersionDbService, new PipelineConverterImpl());
        service = new IndexVersionServiceImpl(indexVersionDbService, kbAuditLogDbService, milvusIndexPort,
                buildService, stateSupport, TransactionTestSupport.template());
    }

    /**
     * 回退：指针切回、旧版退役、目标复活并写审计，随后驱动构建补齐。
     *
     * <p>守护 {@code rollback}：漏切指针或漏调用补齐即失败。
     */
    @Test
    void rollbackShouldSwitchBackPointerAndAudit() {
        WbIndexVersion target = versionRow(15L, "v1", IndexVersionStatus.RETIRED.name());
        WbIndexVersion current = versionRow(10L, "v2", IndexVersionStatus.ONLINE.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexVersionDbService.getById(10L)).thenReturn(current);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        service.rollback(15L);

        assertEquals(IndexVersionStatus.RETIRED.name(), current.getStatus());
        assertEquals(IndexVersionStatus.ONLINE.name(), target.getStatus());
        assertNotNull(target.getPublishedAt());
        verify(indexSetDbService).updatePublishedVersion(5L, 15L);
        verify(kbAuditLogDbService).saveAudit(AuditActionType.ROLLBACK_INDEX, "INDEX_VERSION", 15L, "v2", "v1");
        verify(buildService).compensate(set, target);
    }

    /**
     * 回退：目标即当前在线版本按 40001 拒绝。
     *
     * <p>守护 {@code rollback}：原地回退即失败。
     */
    @Test
    void rollbackShouldForbidWhenTargetIsCurrent() {
        WbIndexVersion target = versionRow(10L, "v1", IndexVersionStatus.ONLINE.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(indexVersionDbService.getById(10L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        WorkbuddyException ex = assertThrows(WorkbuddyException.class, () -> service.rollback(10L));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(buildService, never()).compensate(any(), any());
    }

    /**
     * 回退：目标版本快照缺环节维度按 40448 拒绝。
     *
     * <p>守护 {@code rollback}：静默降级即失败。
     */
    @Test
    void rollbackWhenTargetComboIsLegacyShouldFailWithExplicitError() {
        WbIndexVersion target = versionRow(15L, "v1", IndexVersionStatus.RETIRED.name());
        target.setComboSnapshot("{\"fileScopeMode\":\"ALL\",\"shape\":\"FULL_VECTOR\"}");
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());

        WorkbuddyException ex = assertThrows(WorkbuddyException.class, () -> service.rollback(15L));

        assertEquals(ErrorCode.INDEX_COMBO_SNAPSHOT_LEGACY.getCode(), ex.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
        verify(buildService, never()).compensate(any(), any());
    }

    /**
     * 回退：冻结集版本禁止回退（40449）。
     *
     * <p>守护 {@code rollback}：评测集合切回即失败。
     */
    @Test
    void rollbackShouldRejectFrozenScopeTarget() {
        WbIndexVersion target = versionRow(15L, "v9", IndexVersionStatus.READY.name());
        target.setComboSnapshot(JsonUtil.toJsonStr(comboList(java.util.List.of(10L), "embed-default-v1")));
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());

        WorkbuddyException ex = assertThrows(WorkbuddyException.class, () -> service.rollback(15L));

        assertEquals(ErrorCode.INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN.getCode(), ex.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
        verify(kbAuditLogDbService, never()).saveAudit(any(), any(), anyLong(), any(), any());
    }

    /**
     * 回退：目标/集合缺失 40441、当前无在线版本 40443、目标构建中 40443。
     *
     * <p>守护 {@code rollback} 的四道前置校验：任何一道放行都会切到不可用版本。
     */
    @Test
    void rollbackShouldGuardTargetSetPointerAndBuildingStatus() {
        when(indexVersionDbService.getById(15L)).thenReturn(null);
        WorkbuddyException noTarget = assertThrows(WorkbuddyException.class, () -> service.rollback(15L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noTarget.getCode());

        WbIndexVersion target = versionRow(15L, "v1", IndexVersionStatus.RETIRED.name());
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(null);
        WorkbuddyException noSet = assertThrows(WorkbuddyException.class, () -> service.rollback(15L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noSet.getCode());

        WbIndexSet noPointer = indexSet();
        when(indexSetDbService.getById(5L)).thenReturn(noPointer);
        WorkbuddyException notPublished = assertThrows(WorkbuddyException.class, () -> service.rollback(15L));
        assertEquals(ErrorCode.INDEX_NOT_PUBLISHED.getCode(), notPublished.getCode());

        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(indexSetDbService.getById(5L)).thenReturn(set);
        WbIndexVersion building = versionRow(15L, "v1", IndexVersionStatus.BUILDING.name());
        when(indexVersionDbService.getById(15L)).thenReturn(building);
        WorkbuddyException conflict = assertThrows(WorkbuddyException.class, () -> service.rollback(15L));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), conflict.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
    }

    /**
     * 回退复活目标版本时要清掉退役留痕。
     *
     * <p>守护 {@code rollback} 的复活清场：留着 retiredAt/retiredBy 等于版本既在线又已退役。
     */
    @Test
    void rollbackShouldClearRetiredTraceOnTarget() {
        WbIndexVersion target = versionRow(15L, "v1", IndexVersionStatus.RETIRED.name());
        target.setRetiredAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        target.setRetiredBy("ops");
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        WbIndexVersion current = versionRow(10L, "v2", IndexVersionStatus.ONLINE.name());
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexVersionDbService.getById(10L)).thenReturn(current);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        service.rollback(15L);

        assertNull(target.getRetiredAt());
        assertNull(target.getRetiredBy());
        assertEquals(IndexVersionStatus.ONLINE.name(), target.getStatus());
        verify(buildService).compensate(set, target);
    }

    /**
     * 回收：在线版本禁止删除（40446）。
     *
     * <p>守护 {@code recycle}：在线集合被删即失败。
     */
    @Test
    void recycleShouldForbidOnlineVersion() {
        WbIndexVersion version = versionRow(30L, "v3", IndexVersionStatus.ONLINE.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(30L);
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        WorkbuddyException ex = assertThrows(WorkbuddyException.class, () -> service.recycle(30L));
        assertEquals(ErrorCode.INDEX_ONLINE_DELETE_FORBIDDEN.getCode(), ex.getCode());
        verify(milvusIndexPort, never()).drop(anyString());
    }

    /**
     * 回收：先删集合再删版本行并写 RECYCLE 审计。
     *
     * <p>守护 {@code recycle}：删行不删集合即失败。
     */
    @Test
    void recycleShouldDeleteMilvusRowsAndAudit() {
        WbIndexVersion version = versionRow(30L, "v3", IndexVersionStatus.READY.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        service.recycle(30L);

        verify(milvusIndexPort).drop("kb_1_v3");
        verify(kbAuditLogDbService).saveAudit(AuditActionType.RECYCLE_INDEX, "INDEX_VERSION", 30L, "v3", null);
        verify(indexVersionDbService).removeById(30L);
        InOrder externalFirst = inOrder(milvusIndexPort, kbAuditLogDbService, indexVersionDbService);
        externalFirst.verify(milvusIndexPort).drop("kb_1_v3");
        externalFirst.verify(kbAuditLogDbService)
                .saveAudit(AuditActionType.RECYCLE_INDEX, "INDEX_VERSION", 30L, "v3", null);
        externalFirst.verify(indexVersionDbService).removeById(30L);
    }

    /**
     * 回收：构建中版本与已退役版本都不允许回收。
     *
     * <p>守护 {@code recycle} 的两道状态护栏：退役版本是回退目标，删了就回不去。
     */
    @Test
    void recycleShouldRejectBuildingAndRetiredVersions() {
        WbIndexVersion building = versionRow(30L, "v3", IndexVersionStatus.BUILDING.name());
        when(indexVersionDbService.getById(30L)).thenReturn(building);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());
        WorkbuddyException buildingError = assertThrows(WorkbuddyException.class,
                () -> service.recycle(30L));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), buildingError.getCode());

        WbIndexVersion retired = versionRow(31L, "v2", IndexVersionStatus.RETIRED.name());
        when(indexVersionDbService.getById(31L)).thenReturn(retired);
        WorkbuddyException retiredError = assertThrows(WorkbuddyException.class,
                () -> service.recycle(31L));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), retiredError.getCode());
        verify(milvusIndexPort, never()).drop(anyString());
        verify(indexVersionDbService, never()).removeById(anyLong());
    }
}
