package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.index.IndexVersionStateSupport;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.common.enums.index.IndexShape;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.common.convert.knowledge.PipelineConverterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.comboAll;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.comboList;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.indexSet;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.versionRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布与查询单测：发布三级指针 + 旧版退役 + 审计、当前在线版本、版本列表/详情与错误码。
 */
class IndexPublishServiceImplTest {

    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    private WbIndexSetDbService indexSetDbService;
    private WbIndexVersionDbService indexVersionDbService;
    private WbAuditLogDbService kbAuditLogDbService;
    private IndexPublishServiceImpl service;

    @BeforeEach
    void setUp() {
        knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
        indexSetDbService = mock(WbIndexSetDbService.class);
        indexVersionDbService = mock(WbIndexVersionDbService.class);
        kbAuditLogDbService = mock(WbAuditLogDbService.class);
        IndexVersionStateSupport stateSupport =
                new IndexVersionStateSupport(indexSetDbService, indexVersionDbService, new PipelineConverterImpl());
        service = new IndexPublishServiceImpl(knowledgeBaseDbService, indexSetDbService,
                indexVersionDbService, kbAuditLogDbService, stateSupport);
    }

    /**
     * 发布：旧版退役、指针切换、知识库回填、PUBLISH 审计。
     *
     * <p>守护 {@code publish}：漏退役或漏审计即失败。
     */
    @Test
    void publishShouldSwitchPointersAndAudit() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        WbIndexVersion old = versionRow(10L, "v1", IndexVersionStatus.ONLINE.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);

        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(set);
        when(indexVersionDbService.getById(10L)).thenReturn(old);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);

        service.publish(20L);

        assertEquals(IndexVersionStatus.RETIRED.name(), old.getStatus());
        assertNotNull(old.getRetiredAt());
        assertEquals(IndexVersionStatus.ONLINE.name(), version.getStatus());
        assertNotNull(version.getPublishedAt());
        verify(indexSetDbService).updatePublishedVersion(5L, 20L);
        assertEquals(5L, kb.getPublishedIndexSetId());
        verify(knowledgeBaseDbService).updateById(kb);
        verify(kbAuditLogDbService).saveAudit(AuditActionType.PUBLISH_INDEX, "INDEX_VERSION", 20L, "v1", "v2");
    }

    /**
     * 发布：非就绪版本拒绝（40443）。
     *
     * <p>守护 {@code publish}：未就绪上线即失败。
     */
    @Test
    void publishWhenNotReadyShouldThrow40443() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.BUILDING.name());
        WbIndexSet set = indexSet();
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        WorkbuddyException ex = assertThrows(WorkbuddyException.class, () -> service.publish(20L));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), ex.getCode());
    }

    /**
     * 发布：冻结集版本禁止发布（40449）。
     *
     * <p>守护 {@code publish}：评测集合上线即失败。
     */
    @Test
    void publishShouldRejectFrozenScopeVersion() {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(20L);
        version.setVersionNo("v9");
        version.setComboSnapshot(JsonUtil.toJsonStr(comboList(List.of(10L), "embed-default-v1")));
        when(indexVersionDbService.getById(20L)).thenReturn(version);

        WorkbuddyException ex = assertThrows(WorkbuddyException.class, () -> service.publish(20L));

        assertEquals(ErrorCode.INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN.getCode(), ex.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
    }

    /**
     * 当前在线版本：无指针返回 null。
     *
     * <p>守护 {@code currentPublished}：空指针即失败。
     */
    @Test
    void currentPublishedShouldReturnNullWhenNoPointer() {
        WbIndexSet set = indexSet();
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        assertNull(service.currentPublished(1L));
    }

    /**
     * 当前在线版本：按指针取版本行。
     *
     * <p>守护 {@code currentPublished}：取错行即失败。
     */
    @Test
    void currentPublishedShouldReturnVersion() {
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        WbIndexVersion version = new WbIndexVersion();
        version.setId(10L);
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        when(indexVersionDbService.getById(10L)).thenReturn(version);
        assertEquals(version, service.currentPublished(1L));
    }

    /**
     * 版本列表：在线标记与组合快照展开。
     *
     * <p>守护 {@code listVersions}：在线标记或组合字段错即失败。
     */
    @Test
    void listVersionsShouldMarkOnlineAndOrder() {
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(20L);
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        when(indexVersionDbService.listByIndexSetId(5L))
                .thenReturn(List.of(versionRow(20L, "v2", IndexVersionStatus.READY.name()),
                        versionRow(10L, "v1", IndexVersionStatus.RETIRED.name())));

        var versions = service.listVersions(1L);

        assertEquals(2, versions.size());
        assertTrue(versions.getFirst().isOnline());
        assertFalse(versions.get(1).isOnline());
        assertEquals("chunk-hybrid-v1", versions.getFirst().getChunkStrategy());
        assertEquals("embed-default-v1", versions.getFirst().getEmbedStrategy());
        assertEquals("preproc-default-v1", versions.getFirst().getStageStrategies().get("PREPROCESS"));
        assertEquals(7, versions.getFirst().getChunkCount());
    }

    /**
     * 版本列表：无集合返回空列表。
     *
     * <p>守护 {@code listVersions}：空集合查库即失败。
     */
    @Test
    void listVersionsShouldReturnEmptyWhenNoSet() {
        when(indexSetDbService.getByKb(1L)).thenReturn(null);
        assertTrue(service.listVersions(1L).isEmpty());
    }

    /**
     * 版本详情：版本不属于该库按 40441。
     *
     * <p>守护 {@code versionDetail}：跨库可读即失败。
     */
    @Test
    void versionDetailShouldThrow40441WhenNotBelongsToKb() {
        when(indexSetDbService.getByKb(1L)).thenReturn(indexSet());
        WbIndexVersion version = versionRow(30L, "v3", IndexVersionStatus.READY.name());
        version.setIndexSetId(99L);
        when(indexVersionDbService.getById(30L)).thenReturn(version);

        WorkbuddyException ex = assertThrows(WorkbuddyException.class, () -> service.versionDetail(1L, 30L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), ex.getCode());
    }

    /**
     * 版本详情把版本行的每个字段都带到 VO（含组合快照展开与在线标记）。
     *
     * <p>守护 {@code toVersionVO}：漏搬 buildError/taskId/时间戳等任何一列都会让前端看到空白。
     */
    @Test
    void versionDetailShouldMapEveryVersionField() {
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(20L);
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.ONLINE.name());
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(List.of(10L, 20L));
        version.setComboSnapshot(JsonUtil.toJsonStr(combo));
        version.setBuildError("历史失败原因");
        version.setTaskId(200L);
        LocalDateTime at = LocalDateTime.of(2024, 5, 1, 10, 0);
        version.setValidatedAt(at);
        version.setPublishedAt(at.plusHours(1));
        version.setPublishedBy("admin");
        version.setRetiredAt(at.plusHours(2));
        version.setRetiredBy("ops");
        version.setCreateTime(at.minusDays(1));
        when(indexVersionDbService.getById(20L)).thenReturn(version);

        IndexVersionVO vo = service.versionDetail(1L, 20L);

        assertEquals(20L, vo.getId());
        assertEquals("v2", vo.getVersionNo());
        assertEquals("LIST", vo.getFileScopeMode());
        assertEquals(List.of(10L, 20L), vo.getFileResultIds());
        assertEquals(IndexShape.FULL_VECTOR, vo.getShape());
        assertEquals("chunk-hybrid-v1", vo.getChunkStrategy());
        assertEquals("embed-default-v1", vo.getEmbedStrategy());
        assertEquals("preproc-default-v1", vo.getStageStrategies().get(PipelineStage.PREPROCESS.name()));
        assertEquals(7, vo.getChunkCount());
        assertEquals(9, vo.getVectorCount());
        assertEquals(IndexVersionStatus.ONLINE.name(), vo.getStatus());
        assertEquals("历史失败原因", vo.getBuildError());
        assertEquals(200L, vo.getTaskId());
        assertEquals(at, vo.getValidatedAt());
        assertEquals(at.plusHours(1), vo.getPublishedAt());
        assertEquals("admin", vo.getPublishedBy());
        assertEquals(at.plusHours(2), vo.getRetiredAt());
        assertEquals("ops", vo.getRetiredBy());
        assertEquals(at.minusDays(1), vo.getCreateTime());
        assertTrue(vo.isOnline());
    }

    /**
     * 版本详情：集合不存在、版本不存在都按 40441 处理。
     *
     * <p>守护 {@code versionDetail} 的两段存在性校验：缺任一段都会 NPE 或泄漏半截数据。
     */
    @Test
    void versionDetailShouldRequireSetAndVersion() {
        when(indexSetDbService.getByKb(1L)).thenReturn(null);
        when(indexVersionDbService.getById(20L)).thenReturn(versionRow(20L, "v2", IndexVersionStatus.READY.name()));
        WorkbuddyException noSet = assertThrows(WorkbuddyException.class,
                () -> service.versionDetail(1L, 20L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noSet.getCode());

        when(indexSetDbService.getByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.getById(20L)).thenReturn(null);
        WorkbuddyException noVersion = assertThrows(WorkbuddyException.class,
                () -> service.versionDetail(1L, 20L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noVersion.getCode());
    }

    /**
     * 发布：版本不存在与集合不存在都按 40441 拒绝，且不切换指针。
     *
     * <p>守护 {@code requireVersion}/{@code requireSetOf} 的两道兜底。
     */
    @Test
    void publishShouldRequireVersionAndSet() {
        when(indexVersionDbService.getById(20L)).thenReturn(null);
        WorkbuddyException noVersion = assertThrows(WorkbuddyException.class, () -> service.publish(20L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noVersion.getCode());

        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(null);
        WorkbuddyException noSet = assertThrows(WorkbuddyException.class, () -> service.publish(20L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noSet.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
    }

    /**
     * 版本行没有组合快照：发布按 40001 拒绝并给出文案。
     *
     * <p>守护 {@code requireVersionCombo} 的 null 校验：漏校验会在解析后空指针。
     */
    @Test
    void publishShouldRejectVersionWithoutComboSnapshot() {
        WbIndexVersion noCombo = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        noCombo.setComboSnapshot(null);
        when(indexVersionDbService.getById(20L)).thenReturn(noCombo);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.publish(20L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("版本组合快照缺失"), e.getMessage());
    }
}
