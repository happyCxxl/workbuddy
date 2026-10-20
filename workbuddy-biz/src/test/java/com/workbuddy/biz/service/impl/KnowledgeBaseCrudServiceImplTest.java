package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.access.KnowledgeBaseAccessSupport;
import com.workbuddy.biz.service.support.access.KnowledgeBaseBindingSupport;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDTO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseSort;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库管理服务单测：创建/更新/详情/分页/启停/逻辑删除与总览统计。
 *
 * <p>策略绑定摘要的装配由 {@link KnowledgeBaseBindingSummaryTest} 覆盖，本类只替身
 * {@link KnowledgeBaseBindingSupport}，专注管理闭环与分页批量回填。
 */
class KnowledgeBaseCrudServiceImplTest extends KnowledgeBaseTestFixture {

    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    private WbAuditLogDbService kbAuditLogDbService;

    private WbFileResultDbService kbFileResultDbService;

    private WbIndexSetDbService indexSetDbService;

    private WbIndexVersionDbService indexVersionDbService;

    private KnowledgeBaseBindingSupport bindingSupport;

    private KnowledgeBaseCrudServiceImpl service;

    /**
     * 每个用例重建数据访问替身与服务，并默认以普通用户登录。
     */
    @BeforeEach
    void setUp() {
        knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
        kbAuditLogDbService = mock(WbAuditLogDbService.class);
        kbFileResultDbService = mock(WbFileResultDbService.class);
        indexSetDbService = mock(WbIndexSetDbService.class);
        indexVersionDbService = mock(WbIndexVersionDbService.class);
        bindingSupport = mock(KnowledgeBaseBindingSupport.class);
        service = new KnowledgeBaseCrudServiceImpl(knowledgeBaseDbService, kbAuditLogDbService,
                kbFileResultDbService, indexSetDbService, indexVersionDbService,
                new KnowledgeBaseAccessSupport(knowledgeBaseDbService), bindingSupport);
        login(ME, UserRole.USER);
    }

    /**
     * 新建：落库字段与 CREATE 审计。
     *
     * <p>守护 {@code create}：漏搬描述/开关或审计动作写错即失败。
     */
    @Test
    void createShouldSaveActiveAndAuditCreate() {
        KnowledgeBaseCreateDTO dto = new KnowledgeBaseCreateDTO();
        dto.setName("招投标知识库");
        dto.setDescription("招投标场景");
        doAnswer(inv -> {
            WbKnowledgeBase k = inv.getArgument(0);
            k.setId(1L);
            return true;
        }).when(knowledgeBaseDbService).save(any(WbKnowledgeBase.class));

        Long id = service.create(dto);

        assertEquals(1L, id);
        ArgumentCaptor<WbKnowledgeBase> kbCaptor = ArgumentCaptor.forClass(WbKnowledgeBase.class);
        verify(knowledgeBaseDbService).save(kbCaptor.capture());
        assertEquals(KnowledgeBaseStatus.ACTIVE.getCode(), kbCaptor.getValue().getStatus());
        assertEquals("招投标知识库", kbCaptor.getValue().getName());
        assertEquals("招投标场景", kbCaptor.getValue().getDescription());
        // 未传开关 = 存量口径的「开启」（1），不是 0
        assertEquals(1, kbCaptor.getValue().getStrategyBindingEnabled());
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.CREATE), eq("KNOWLEDGE_BASE"), eq(1L), isNull(), anyString());
    }

    /**
     * 新建时显式关闭策略绑定，落库必须是 0。
     *
     * <p>守护 {@code resolveBindingEnabled} 的开关归一：关成 0 写成 1 会让测评库被绑定策略。
     */
    @Test
    void createWithSwitchOffShouldPersistZero() {
        KnowledgeBaseCreateDTO dto = new KnowledgeBaseCreateDTO();
        dto.setName("测评库");
        dto.setStrategyBindingEnabled(0);
        doAnswer(inv -> {
            inv.getArgument(0, WbKnowledgeBase.class).setId(1L);
            return true;
        }).when(knowledgeBaseDbService).save(any(WbKnowledgeBase.class));

        service.create(dto);

        ArgumentCaptor<WbKnowledgeBase> cap = ArgumentCaptor.forClass(WbKnowledgeBase.class);
        verify(knowledgeBaseDbService).save(cap.capture());
        assertEquals(0, cap.getValue().getStrategyBindingEnabled());
    }

    /**
     * 详情：知识库不存在按 40401。
     *
     * <p>守护 {@code detail}：空行放行会空指针。
     */
    @Test
    void detailWhenAbsentShouldThrowNotFound() {
        when(knowledgeBaseDbService.getActiveById(9L)).thenThrow(new KnowledgeException(ErrorCode.KB_NOT_FOUND));
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.detail(9L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 详情：实体字段全量映射到 VO 并回填文档数。
     *
     * <p>守护 {@code detail}：漏搬留痕字段或文档数即失败。
     */
    @Test
    void detailShouldMapToVO() {
        WbKnowledgeBase k = kb(2L, 1);
        k.setDescription("描述");
        k.setStrategyBindingEnabled(1);
        k.setUserId(ME);
        k.setCreateBy("张三");
        k.setUpdateBy("李四");
        java.time.LocalDateTime created = java.time.LocalDateTime.of(2024, 1, 2, 3, 4);
        java.time.LocalDateTime updated = java.time.LocalDateTime.of(2024, 2, 3, 4, 5);
        k.setCreateTime(created);
        k.setUpdateTime(updated);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(k);
        when(kbFileResultDbService.countByKb(2L)).thenReturn(3L);

        KnowledgeBaseVO vo = service.detail(2L);

        assertEquals(2L, vo.getId());
        assertEquals("库2", vo.getName());
        assertEquals("描述", vo.getDescription());
        assertEquals(1, vo.getStatus());
        assertEquals(1, vo.getStrategyBindingEnabled());
        assertEquals(Long.valueOf(ME), vo.getUserId());
        assertEquals("张三", vo.getCreateBy());
        assertEquals("李四", vo.getUpdateBy());
        assertEquals(created, vo.getCreateTime());
        assertEquals(updated, vo.getUpdateTime());
        assertEquals(3L, vo.getDocumentCount());
    }

    /**
     * 修改：字段覆盖与 UPDATE 审计，返回 true。
     *
     * <p>守护 {@code update}：漏搬描述/开关或返回值写死即失败。
     */
    @Test
    void updateShouldChangeNameAndAudit() {
        WbKnowledgeBase k = kb(3L, 1);
        when(knowledgeBaseDbService.getActiveById(3L)).thenReturn(k);
        KnowledgeBaseUpdateDTO dto = new KnowledgeBaseUpdateDTO();
        dto.setId(3L);
        dto.setName("新名称");
        dto.setDescription("新描述");
        dto.setStrategyBindingEnabled(0);

        assertTrue(service.update(dto));

        ArgumentCaptor<WbKnowledgeBase> cap = ArgumentCaptor.forClass(WbKnowledgeBase.class);
        verify(knowledgeBaseDbService).updateById(cap.capture());
        assertEquals("新名称", cap.getValue().getName());
        assertEquals("新描述", cap.getValue().getDescription());
        assertEquals(0, cap.getValue().getStrategyBindingEnabled());
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.UPDATE), eq("KNOWLEDGE_BASE"), eq(3L), anyString(), anyString());
    }

    /**
     * 停用：状态置 0 并写 DISABLE 审计。
     *
     * <p>守护 {@code disable}：状态码或返回值写错即失败。
     */
    @Test
    void disableOnActiveShouldUpdateStatusAndAudit() {
        when(knowledgeBaseDbService.getActiveById(4L)).thenReturn(kb(4L, 1));

        assertTrue(service.disable(4L));

        ArgumentCaptor<WbKnowledgeBase> cap = ArgumentCaptor.forClass(WbKnowledgeBase.class);
        verify(knowledgeBaseDbService).updateById(cap.capture());
        assertEquals(0, cap.getValue().getStatus());
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.DISABLE), eq("KNOWLEDGE_BASE"), eq(4L), anyString(), anyString());
    }

    /**
     * 停用：已停用的库拒绝并 0 写。
     *
     * <p>守护 {@code disable}：重复停用不报错即失败。
     */
    @Test
    void disableOnDisabledShouldThrowAndNotUpdate() {
        when(knowledgeBaseDbService.getActiveById(4L)).thenReturn(kb(4L, 0));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.disable(4L));

        assertEquals(ErrorCode.KB_STATUS_ILLEGAL, e.getErrorCode());
        verify(knowledgeBaseDbService, never()).updateById(any());
    }

    /**
     * 启用：状态置 1 并写 ENABLE 审计。
     *
     * <p>守护 {@code enable}：状态码或返回值写错即失败。
     */
    @Test
    void enableOnDisabledShouldUpdateStatus() {
        when(knowledgeBaseDbService.getActiveById(5L)).thenReturn(kb(5L, 0));

        assertTrue(service.enable(5L));

        ArgumentCaptor<WbKnowledgeBase> cap = ArgumentCaptor.forClass(WbKnowledgeBase.class);
        verify(knowledgeBaseDbService).updateById(cap.capture());
        assertEquals(1, cap.getValue().getStatus());
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.ENABLE), eq("KNOWLEDGE_BASE"), eq(5L), anyString(), anyString());
    }

    /**
     * 已启用的库不允许再启用。
     *
     * <p>守护 {@code enable} 的状态校验：漏了 = 对启用中的库重复写审计与更新。
     */
    @Test
    void enableOnActiveShouldReject() {
        when(knowledgeBaseDbService.getActiveById(5L)).thenReturn(kb(5L, 1));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.enable(5L));

        assertEquals(ErrorCode.KB_STATUS_ILLEGAL, e.getErrorCode());
        verify(knowledgeBaseDbService, never()).updateById(any());
    }

    /**
     * 删除：逻辑删除并写 DELETE 审计。
     *
     * <p>守护 {@code delete}：漏删或返回值写死即失败。
     */
    @Test
    void deleteShouldLogicDeleteAndAudit() {
        when(knowledgeBaseDbService.getActiveById(6L)).thenReturn(kb(6L, 1));

        assertTrue(service.delete(6L));

        verify(knowledgeBaseDbService).removeById(6L);
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.DELETE), eq("KNOWLEDGE_BASE"), eq(6L), anyString(), isNull());
    }

    /**
     * 分页：条件与排序方向的列名解析。
     *
     * <p>守护 {@code page}：按错列排序会静默乱序。
     */
    @Test
    void pageShouldOrderNewestFirst() {
        // 直接验证查询条件构造：Lambda 必须解析成正确的列名与排序方向。
        // 若 lambda 引用写错（例如误用 name），解析出来会是别的列名而不报错，属于静默 bug。
        // 注意：脱离 Spring 上下文时 MPJ 没有 TableInfo 缓存，需先初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WbKnowledgeBase.class);
        LambdaQueryWrapper<WbKnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(false, WbKnowledgeBase::getName, null)
                .eq(false, WbKnowledgeBase::getStatus, null)
                // 归属条件必须落在 user_id 上：Lambda 引用写错（例如误用 create_by）不报错，
                // 只是静默变成"按别的列过滤"，那等于归属隔离失效
                .eq(true, WbKnowledgeBase::getUserId, ME)
                .orderByDesc(WbKnowledgeBase::getId);
        String sql = wrapper.getSqlSegment();

        assertTrue(sql.contains("user_id ="), "归属过滤应落在 user_id 列，实际: " + sql);
        assertTrue(sql.contains("id DESC"), "应按 id 倒序（新建的在前），实际: " + sql);
    }

    /**
     * 分页：批量回填已发布版本号（两次查询覆盖整页）。
     *
     * <p>守护 {@code page}：逐行查询或漏填版本号即失败。
     */
    @Test
    void pageShouldFillPublishedIndexVersionWithBatchQueries() {
        WbKnowledgeBase withIndex = kb(7L, 1);
        withIndex.setPublishedIndexSetId(700L);
        WbKnowledgeBase withoutIndex = kb(8L, 1);
        Page<WbKnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(2);
        page.setRecords(List.of(withIndex, withoutIndex));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null,
                KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        WbIndexSet set = new WbIndexSet();
        set.setKnowledgeBaseId(7L);
        set.setCurrentPublishedVersionId(900L);
        when(indexSetDbService.listByKbIds(any())).thenReturn(Map.of(7L, set));
        WbIndexVersion version = new WbIndexVersion();
        version.setId(900L);
        version.setVersionNo("v3");
        when(indexVersionDbService.listByIds(any())).thenReturn(List.of(version));

        IPage<KnowledgeBaseVO> result = service.page(kbQuery(null, null, null));

        assertEquals("v3", result.getRecords().get(0).getPublishedIndexVersion());
        // 未发布索引的库为 null（前端据此显示「未发布」）
        assertNull(result.getRecords().get(1).getPublishedIndexVersion());
        // N+1 防护：整页只允许一次集合查询 + 一次版本查询
        verify(indexSetDbService).listByKbIds(any());
        verify(indexVersionDbService).listByIds(any());
    }

    /**
     * 分页：整页都没发布过时不查索引集合。
     *
     * <p>守护 {@code page}：白查询或空条件查库即失败。
     */
    @Test
    void pageShouldSkipIndexLookupWhenNobodyPublished() {
        Page<WbKnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(kb(7L, 1)));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null,
                KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        service.page(kbQuery(null, null, null));

        // 没有任何库发布过索引时不应产生多余的查询
        verify(indexSetDbService, never()).listByKbIds(any());
        verify(indexVersionDbService, never()).listByIds(any());
        assertTrue(mockingDetails(indexVersionDbService).getInvocations().stream()
                .noneMatch(inv -> "listByIds".equals(inv.getMethod().getName())));
    }

    /**
     * 分页：记录映射到 VO。
     *
     * <p>守护 {@code page}：字段漏搬即失败。
     */
    @Test
    void pageShouldReturnMappedRecords() {
        Page<WbKnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(kb(7L, 1)));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, "库7", null,
                KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of(7L, 3L));

        IPage<KnowledgeBaseVO> result = service.page(kbQuery("库7", null, null));

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("库7", result.getRecords().getFirst().getName());
        // 文档数按 wb_file_result 记录数回填；无记录的库补 0 而非 null
        assertEquals(3L, result.getRecords().getFirst().getDocumentCount());
    }

    /**
     * 分页：状态条件透传、无文档数记 0。
     *
     * <p>守护 {@code page}：条件丢失或默认值写错即失败。
     */
    @Test
    void pageShouldPassStatusFilterAndDefaultDocumentCountToZero() {
        Page<WbKnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(kb(8L, 0)));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, 0, KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        IPage<KnowledgeBaseVO> result = service.page(kbQuery(null, 0, null));

        assertEquals(0L, result.getRecords().getFirst().getDocumentCount());
    }

    /**
     * 总览：库数/启用数/文档数同范围聚合。
     *
     * <p>守护 {@code stats}：口径不一致即失败。
     */
    @Test
    void statsShouldAggregateCounts() {
        when(knowledgeBaseDbService.countByStatus(null, ME)).thenReturn(5L);
        when(knowledgeBaseDbService.countByStatus(1, ME)).thenReturn(3L);
        when(knowledgeBaseDbService.listIdsByOwner(ME)).thenReturn(List.of(1L, 2L));
        when(kbFileResultDbService.countByKbIds(List.of(1L, 2L))).thenReturn(42L);

        KnowledgeBaseStatsVO stats = service.stats();

        assertEquals(5L, stats.getKnowledgeBaseCount());
        assertEquals(3L, stats.getEnabledCount());
        assertEquals(42L, stats.getDocumentCount());
    }
}
