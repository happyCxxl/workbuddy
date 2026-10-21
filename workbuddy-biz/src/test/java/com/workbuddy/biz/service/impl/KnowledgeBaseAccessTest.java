package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.access.KnowledgeBaseAccessSupport;
import com.workbuddy.common.convert.KnowledgeConverterImpl;
import com.workbuddy.biz.service.support.access.KnowledgeBaseBindingSupport;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateDTO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseSort;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库归属可见范围单测：未登录拒绝、越权按不存在处理、管理员不限归属、列表与统计同范围。
 *
 * <p>口径（见 KnowledgeBaseRules）：普通用户只能读写自己创建的库，管理员不限归属；
 * 越权一律按「不存在」（KB_NOT_FOUND 40401）处理，不泄露「这个库存在，只是不是你的」。
 */
class KnowledgeBaseAccessTest extends KnowledgeBaseTestFixture {

    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    private WbAuditLogDbService kbAuditLogDbService;

    private WbFileResultDbService kbFileResultDbService;

    private WbStrategyBindingDbService strategyBindingDbService;

    private WbPipelineStrategyVersionDbService strategyVersionDbService;

    private KnowledgeBaseCrudServiceImpl crudService;

    private KnowledgeBaseBindingServiceImpl bindingService;

    /**
     * 每个用例重建真实归属助手与两个被测服务，默认以普通用户登录。
     */
    @BeforeEach
    void setUp() {
        knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
        kbAuditLogDbService = mock(WbAuditLogDbService.class);
        kbFileResultDbService = mock(WbFileResultDbService.class);
        strategyBindingDbService = mock(WbStrategyBindingDbService.class);
        strategyVersionDbService = mock(WbPipelineStrategyVersionDbService.class);
        KnowledgeBaseAccessSupport accessSupport = new KnowledgeBaseAccessSupport(knowledgeBaseDbService);
        KnowledgeBaseBindingSupport bindingSupport =
                new KnowledgeBaseBindingSupport(strategyBindingDbService, strategyVersionDbService);
        crudService = new KnowledgeBaseCrudServiceImpl(knowledgeBaseDbService, kbAuditLogDbService,
                kbFileResultDbService, mock(WbIndexSetDbService.class), mock(WbIndexVersionDbService.class),
                accessSupport, bindingSupport, new KnowledgeConverterImpl());
        bindingService = new KnowledgeBaseBindingServiceImpl(kbAuditLogDbService, strategyBindingDbService,
                strategyVersionDbService, accessSupport, bindingSupport);
        login(ME, UserRole.USER);
    }

    /**
     * 新建：必须带归属，无登录上下文拒绝。
     *
     * <p>守护 {@code create}：落孤儿数据或放行未认证即失败。
     */
    @Test
    void createShouldStampOwnerAndRejectWithoutLogin() {
        KnowledgeBaseCreateDTO dto = new KnowledgeBaseCreateDTO();
        dto.setName("我的库");
        doAnswer(inv -> {
            inv.getArgument(0, WbKnowledgeBase.class).setId(1L);
            return true;
        }).when(knowledgeBaseDbService).save(any(WbKnowledgeBase.class));

        crudService.create(dto);

        ArgumentCaptor<WbKnowledgeBase> cap = ArgumentCaptor.forClass(WbKnowledgeBase.class);
        verify(knowledgeBaseDbService).save(cap.capture());
        assertEquals(Long.valueOf(ME), cap.getValue().getUserId(),
                "知识库必须带归属：user_id 为空的行普通用户谁也看不见");

        // 无登录上下文：拒绝，而不是静默落一条 user_id = NULL 的孤儿数据
        SecurityContextHolder.clearContext();
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> crudService.create(dto));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    /**
     * 详情：无登录上下文拒绝（40101）。
     *
     * <p>守护 {@code detail}：未认证放行即失败。
     */
    @Test
    void detailWithoutLoginShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        SecurityContextHolder.clearContext();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> crudService.detail(2L));

        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    /**
     * 详情：别人的库按不存在处理（40401）。
     *
     * <p>守护 {@code detail}：越权可读即失败。
     */
    @Test
    void detailOnOthersKbShouldBehaveAsNotFound() {
        WbKnowledgeBase others = kb(9L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(9L)).thenReturn(others);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> crudService.detail(9L));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 详情：存量无归属库对普通用户不可见。
     *
     * <p>守护 {@code detail}：存量数据变公共资产即失败。
     */
    @Test
    void detailOnLegacyKbWithoutOwnerShouldBeInvisibleToNormalUser() {
        // 存量 user_id = NULL 的早期数据：普通用户不可见（不是公共资产）
        WbKnowledgeBase legacy = kb(9L, 1);
        legacy.setUserId(null);
        when(knowledgeBaseDbService.getActiveById(9L)).thenReturn(legacy);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> crudService.detail(9L));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 修改：别人的库拒绝且 0 写。
     *
     * <p>守护 {@code update}：越权可改即失败。
     */
    @Test
    void updateOnOthersKbShouldThrowAndNotUpdate() {
        WbKnowledgeBase others = kb(3L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(3L)).thenReturn(others);
        KnowledgeBaseUpdateDTO dto = new KnowledgeBaseUpdateDTO();
        dto.setId(3L);
        dto.setName("改别人的库");

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> crudService.update(dto));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(knowledgeBaseDbService, never()).updateById(any());
    }

    /**
     * 停用：别人的库拒绝且 0 写。
     *
     * <p>守护 {@code disable}：越权可停即失败。
     */
    @Test
    void disableOnOthersKbShouldThrowAndNotUpdate() {
        WbKnowledgeBase others = kb(4L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(4L)).thenReturn(others);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> crudService.disable(4L));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(knowledgeBaseDbService, never()).updateById(any());
    }

    /**
     * 删除：别人的库拒绝且 0 删。
     *
     * <p>守护 {@code delete}：越权可删即失败。
     */
    @Test
    void deleteOnOthersKbShouldThrowAndNotRemove() {
        WbKnowledgeBase others = kb(6L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(6L)).thenReturn(others);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> crudService.delete(6L));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(knowledgeBaseDbService, never()).removeById(any());
    }

    /**
     * 批量绑定：别人的库拒绝且 0 写。
     *
     * <p>守护 {@code bindStrategies}：越权可绑即失败。
     */
    @Test
    void bindStrategiesOnOthersKbShouldThrowAndNotSave() {
        WbKnowledgeBase others = kb(2L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(others);
        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", 33L));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> bindingService.bindStrategies(2L, request));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 管理员：可读别人的库，列表不加归属过滤。
     *
     * <p>守护 {@code page}：管理员被归属过滤挡住即失败。
     */
    @Test
    void adminShouldAccessOthersKbAndSkipOwnerFilter() {
        login(ME, UserRole.ADMIN);
        WbKnowledgeBase others = kb(9L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(9L)).thenReturn(others);
        Page<WbKnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(kb(7L, 1)));
        // 管理员：归属条件为 null（不加 user_id 过滤）
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null,
                KnowledgeBaseSort.DEFAULT, null)).thenReturn(page);
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        assertEquals(9L, crudService.detail(9L).getId(), "管理员可读别人的库");

        crudService.page(kbQuery(null, null, null));

        verify(knowledgeBaseDbService).pageByCondition(1L, 10L, null, null, KnowledgeBaseSort.DEFAULT, null);
    }

    /**
     * 普通用户：列表与计数都按归属收口，空页不查绑定。
     *
     * <p>守护 {@code page}：范围扩散到全平台即失败。
     */
    @Test
    void pageAndStatsShouldScopeToCurrentUser() {
        Page<WbKnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(0);
        page.setRecords(List.of());
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null,
                KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(knowledgeBaseDbService.countByStatus(null, ME)).thenReturn(2L);
        when(knowledgeBaseDbService.countByStatus(1, ME)).thenReturn(1L);
        when(knowledgeBaseDbService.listIdsByOwner(ME)).thenReturn(List.of(7L, 8L));
        when(kbFileResultDbService.countByKbIds(List.of(7L, 8L))).thenReturn(9L);

        crudService.page(kbQuery(null, null, null));
        KnowledgeBaseStatsVO stats = crudService.stats();

        // 列表与计数都按当前用户归属收口；文档数也取同一范围（不是全平台 countAll）
        verify(knowledgeBaseDbService).pageByCondition(1L, 10L, null, null, KnowledgeBaseSort.DEFAULT, ME);
        // 空页不再去打绑定/版本这类批量查询
        verify(strategyBindingDbService, never()).listActiveByTypeAndKbIds(any(), any());
        assertEquals(2L, stats.getKnowledgeBaseCount());
        assertEquals(1L, stats.getEnabledCount());
        assertEquals(9L, stats.getDocumentCount());
    }
}
