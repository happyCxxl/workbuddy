package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseQueryDTO;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.InputVoAssembler;
import com.workbuddy.biz.service.support.TaskVoAssembler;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateDTO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseSort;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.input.FileValidatorPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库管理应用服务单测（含策略绑定与归属可见范围）。
 */
class KnowledgeBaseServiceImplTest {

    /** 当前登录用户（普通用户）：夹具知识库默认都归它，正向用例才能过归属校验 */
    private static final long ME = 1001L;

    /** 另一个用户：用于「看不到别人的库」的反向用例 */
    private static final long OTHER = 2002L;

    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    private WbAuditLogDbService kbAuditLogDbService;

    private WbStrategyBindingDbService strategyBindingDbService;

    private WbPipelineStrategyVersionDbService strategyVersionDbService;

    private WbFileResultDbService kbFileResultDbService;

    private WbIndexSetDbService indexSetDbService;

    private WbIndexVersionDbService indexVersionDbService;

    private WbSourceFileDbService sourceFileDbService;

    private WbSubmitLogDbService submitLogDbService;

    private WbPipelineTaskDbService pipelineTaskDbService;

    private FileValidatorPort fileValidator;

    private FileStorage fileStorage;

    private StorageRouter storageRouter;

    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
        kbAuditLogDbService = mock(WbAuditLogDbService.class);
        strategyBindingDbService = mock(WbStrategyBindingDbService.class);
        strategyVersionDbService = mock(WbPipelineStrategyVersionDbService.class);
        kbFileResultDbService = mock(WbFileResultDbService.class);
        indexSetDbService = mock(WbIndexSetDbService.class);
        indexVersionDbService = mock(WbIndexVersionDbService.class);
        sourceFileDbService = mock(WbSourceFileDbService.class);
        submitLogDbService = mock(WbSubmitLogDbService.class);
        pipelineTaskDbService = mock(WbPipelineTaskDbService.class);
        fileValidator = mock(FileValidatorPort.class);
        fileStorage = mock(FileStorage.class);
        storageRouter = mock(StorageRouter.class);
        // 存储口径：当前启用的数据源取运行时生效值，列表页的所属数据源按档案批量取（无档案时留空）
        when(storageRouter.writeType()).thenReturn(StorageType.MINIO);
        when(fileStorage.refsOf(any())).thenReturn(Map.of());
        // 组装器为纯映射无状态类，用真实实例（mock 会让 VO 组装返回 null）
        service = new KnowledgeBaseServiceImpl(knowledgeBaseDbService, kbAuditLogDbService,
                strategyBindingDbService, strategyVersionDbService, kbFileResultDbService,
                indexSetDbService, indexVersionDbService, sourceFileDbService, submitLogDbService,
                pipelineTaskDbService, fileValidator, fileStorage, storageRouter,
                new InputVoAssembler(), new TaskVoAssembler());
        // 默认以普通用户登录：可见范围 = 自己的库。管理员视角的用例单独 login(ADMIN)
        login(ME, UserRole.USER);
    }

    @AfterEach
    void tearDown() {
        // 安全上下文是线程级静态，不清理会串到下一个用例
        SecurityContextHolder.clearContext();
    }

    /** 设置登录上下文（服务层归属校验直接读 Spring Security 上下文） */
    private void login(long userId, UserRole role) {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setRole(role);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private WbKnowledgeBase kb(long id, int status) {
        WbKnowledgeBase k = new WbKnowledgeBase();
        k.setId(id);
        k.setName("库" + id);
        k.setStatus(status);
        // 归属：本夹具统一归当前登录用户，反向用例另行 setUserId(OTHER)
        k.setUserId(ME);
        return k;
    }

    /** 有效绑定行（KB 2 绑定 CHUNK） */
    private WbStrategyBinding binding(Long versionId) {
        WbStrategyBinding binding = new WbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(2L);
        binding.setStrategyType("CHUNK");
        binding.setStrategyVersionId(versionId);
        return binding;
    }

    /** 启用中的 CHUNK 策略版本行 */
    private WbPipelineStrategyVersion chunkVersion() {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(66L);
        row.setType("CHUNK");
        row.setName("chunk-hybrid");
        row.setVersion("v1");
        row.setStatus("ACTIVE");
        return row;
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
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
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
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        service.page(kbQuery(null, null, null));

        // 没有任何库发布过索引时不应产生多余的查询
        verify(indexSetDbService, never()).listByKbIds(any());
        verify(indexVersionDbService, never()).listByIds(any());
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(indexVersionDbService).getInvocations().stream()
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
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, "库7", null, KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
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
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
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

    // ---------------- 策略绑定 ----------------

    /**
     * 查绑定：类型、版本 ID/名称/版本号全量返回。
     *
     * <p>守护 {@code strategyBinding}：漏搬任一字段即失败。
     */
    @Test
    void strategyBindingShouldReturnBoundVersion() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding(66L));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());

        StrategyBindingVO vo = service.strategyBinding(2L, "CHUNK");

        assertEquals("CHUNK", vo.getStrategyType());
        assertEquals(66L, vo.getStrategyVersionId());
        assertEquals("chunk-hybrid", vo.getStrategyName());
        assertEquals("v1", vo.getStrategyVersion());
    }

    /**
     * 绑定不存在版本 / 类型与版本不匹配 / 未知类型三种请求一律 40001。
     *
     * <p>守护 {@code bindStrategy} 的三道入参校验：放行任何一个都会把错版本绑上去。
     */
    @Test
    void bindStrategyShouldRejectUnknownTypeMissingVersionAndTypeMismatch() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        StrategyBindingUpdateDTO unknownType = new StrategyBindingUpdateDTO();
        unknownType.setStrategyType("INDEX");
        unknownType.setStrategyVersionId(66L);
        KnowledgeException typeError = assertThrows(KnowledgeException.class,
                () -> service.bindStrategy(2L, unknownType));
        assertEquals(ErrorCode.PARAM_INVALID, typeError.getErrorCode());
        assertTrue(typeError.getMessage().contains("未知策略类型"), typeError.getMessage());

        StrategyBindingUpdateDTO missingVersion = new StrategyBindingUpdateDTO();
        missingVersion.setStrategyType("CHUNK");
        missingVersion.setStrategyVersionId(999L);
        when(strategyVersionDbService.getById(999L)).thenReturn(null);
        KnowledgeException missing = assertThrows(KnowledgeException.class,
                () -> service.bindStrategy(2L, missingVersion));
        assertEquals(ErrorCode.PARAM_INVALID, missing.getErrorCode());
        assertTrue(missing.getMessage().contains("策略版本不存在"), missing.getMessage());

        WbPipelineStrategyVersion embedVersion = new WbPipelineStrategyVersion();
        embedVersion.setId(88L);
        embedVersion.setType("EMBED");
        embedVersion.setStatus("ACTIVE");
        StrategyBindingUpdateDTO mismatch = new StrategyBindingUpdateDTO();
        mismatch.setStrategyType("CHUNK");
        mismatch.setStrategyVersionId(88L);
        when(strategyVersionDbService.getById(88L)).thenReturn(embedVersion);
        KnowledgeException mismatchError = assertThrows(KnowledgeException.class,
                () -> service.bindStrategy(2L, mismatch));
        assertEquals(ErrorCode.PARAM_INVALID, mismatchError.getErrorCode());
        assertTrue(mismatchError.getMessage().contains("策略类型与所选版本不匹配"), mismatchError.getMessage());

        verify(strategyBindingDbService, never()).save(any());
        verify(strategyBindingDbService, never()).updateById(any());
    }

    /**
     * 首次绑定：新行把知识库、类型、版本 ID 全部落库。
     *
     * <p>守护 {@code bindStrategy} 的新建分支：漏写任一列都会让绑定在查询侧失效。
     */
    @Test
    void bindStrategyShouldCreateRowWithAllFields() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(null);
        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        assertTrue(service.bindStrategy(2L, dto));

        ArgumentCaptor<WbStrategyBinding> captor = ArgumentCaptor.forClass(WbStrategyBinding.class);
        verify(strategyBindingDbService).save(captor.capture());
        assertEquals(2L, captor.getValue().getKnowledgeBaseId());
        assertEquals("CHUNK", captor.getValue().getStrategyType());
        assertEquals(66L, captor.getValue().getStrategyVersionId());
    }

    /**
     * 审计里的「变更前」摘要：无绑定与已删行都渲染成「无」。
     *
     * <p>守护 {@code bindingSummary}：摘要写成空串或把已删行当有效绑定即失败。
     */
    @Test
    void bindStrategyAuditShouldRenderMissingAndDeletedBeforeAsNone() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());
        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);
        // ① 无现有绑定 → 「无」
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(null);
        service.bindStrategy(2L, dto);

        // ② 现有行已逻辑删除 → 仍按「无」
        WbStrategyBinding deleted = binding(55L);
        deleted.setDelFlag(DelFlag.DELETED.getCode());
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(deleted);
        service.bindStrategy(2L, dto);

        verify(kbAuditLogDbService, times(2)).saveAudit(eq(AuditActionType.BIND), eq("KNOWLEDGE_BASE"),
                eq(2L), eq("CHUNK=无"), eq("CHUNK=chunk-hybrid-v1"));
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(kbAuditLogDbService).getInvocations().stream()
                .anyMatch(inv -> "saveAudit".equals(inv.getMethod().getName())));
    }

    /**
     * 审计里的「变更前」摘要：绑定指向的版本已不存在时渲染成「失效」。
     *
     * <p>守护 {@code bindingSummary}：跟随失效版本取名字会得到半截摘要。
     */
    @Test
    void bindStrategyAuditShouldRenderInvalidBeforeState() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());
        WbStrategyBinding orphan = binding(999L);
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(orphan);
        when(strategyVersionDbService.getById(999L)).thenReturn(null);
        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        service.bindStrategy(2L, dto);

        verify(kbAuditLogDbService).saveAudit(eq(AuditActionType.BIND), eq("KNOWLEDGE_BASE"), eq(2L),
                eq("CHUNK=失效"), eq("CHUNK=chunk-hybrid-v1"));
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(kbAuditLogDbService).getInvocations().stream()
                .anyMatch(inv -> "saveAudit".equals(inv.getMethod().getName())));
    }

    /**
     * 查绑定：未知类型按 40001 拒绝。
     *
     * <p>守护 {@code strategyBinding}：白名单失效即失败。
     */
    @Test
    void strategyBindingWithUnknownTypeShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.strategyBinding(2L, "INDEX"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 绑定：复用含已逻辑删除的原行并恢复删除标记。
     *
     * <p>守护 {@code bindStrategy}：新建重复行即失败。
     */
    @Test
    void bindStrategyShouldReuseExistingRow() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());
        WbStrategyBinding existing = binding(55L);
        existing.setDelFlag(DelFlag.DELETED.getCode());
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(existing);

        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        assertTrue(service.bindStrategy(2L, dto));

        assertEquals(DelFlag.NORMAL.getCode(), existing.getDelFlag());
        assertEquals(66L, existing.getStrategyVersionId());
        verify(strategyBindingDbService).updateById(existing);
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.BIND), eq("KNOWLEDGE_BASE"), eq(2L), anyString(), anyString());
    }

    /**
     * 绑定：版本为空即解绑（逻辑删除绑定行）。
     *
     * <p>守护 {@code bindStrategy}：解绑不做或审计丢失即失败。
     */
    @Test
    void bindStrategyWithNullVersionShouldUnbind() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding(66L));

        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(null);

        assertTrue(service.bindStrategy(2L, dto));

        ArgumentCaptor<WbStrategyBinding> captor = ArgumentCaptor.forClass(WbStrategyBinding.class);
        verify(strategyBindingDbService).updateById(captor.capture());
        assertEquals(DelFlag.DELETED.getCode(), captor.getValue().getDelFlag());
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.BIND), eq("KNOWLEDGE_BASE"), eq(2L), anyString(), isNull());
    }

    /**
     * 绑定：停用策略拒绝绑定。
     *
     * <p>守护 {@code bindStrategy}：停用版本被绑上去即失败。
     */
    @Test
    void bindStrategyWithInactiveVersionShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        WbPipelineStrategyVersion inactive = chunkVersion();
        inactive.setStatus("INACTIVE");
        when(strategyVersionDbService.getById(66L)).thenReturn(inactive);

        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.bindStrategy(2L, dto));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyBindingDbService, never()).save(any());
    }

    // ---------------- 批量设置绑定（bindStrategies）----------------
    //
    // 本接口的语义是"确定了一套策略组合"，要求一次给全三件套且每项都有版本；
    // 逐类型增量改走 bindStrategy。"开关开着但还没绑"由不调用本接口表达。

    /** 批量绑定请求项 */
    private StrategyBindingsUpdateDTO.StrategyBindItem bindItem(String type, Long versionId) {
        StrategyBindingsUpdateDTO.StrategyBindItem item =
                new StrategyBindingsUpdateDTO.StrategyBindItem();
        item.setStrategyType(type);
        item.setStrategyVersionId(versionId);
        return item;
    }

    /** 批量绑定请求（绑定项可变参数） */
    private StrategyBindingsUpdateDTO bindRequest(
            StrategyBindingsUpdateDTO.StrategyBindItem... items) {
        StrategyBindingsUpdateDTO request = new StrategyBindingsUpdateDTO();
        request.setBindings(List.of(items));
        return request;
    }

    /** 让三件套的版本查询都返回启用中的行（类型与入参一致） */
    private void stubActiveVersions(String type, Long versionId) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(versionId);
        row.setType(type);
        row.setName(type.toLowerCase() + "-x");
        row.setVersion("v1");
        row.setStatus("ACTIVE");
        when(strategyVersionDbService.getById(versionId)).thenReturn(row);
    }

    /**
     * 批量绑定：三件套齐全时逐项绑定成功。
     *
     * <p>守护 {@code bindStrategies}：漏绑任一环节即失败。
     */
    @Test
    void bindStrategiesShouldAcceptFullTriple() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        stubActiveVersions("PREPROCESS", 11L);
        stubActiveVersions("CHUNK", 22L);
        stubActiveVersions("EMBED", 33L);
        when(strategyBindingDbService.getAnyByKbAndType(eq(2L), anyString())).thenReturn(null);

        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", 33L));

        assertTrue(service.bindStrategies(2L, request));

        verify(strategyBindingDbService, times(3)).save(any(WbStrategyBinding.class));
    }

    /**
     * 批量绑定：缺环节按 40001 拒绝。
     *
     * <p>守护 {@code bindStrategies}：缺项被放行即失败。
     */
    @Test
    void bindStrategiesWithPartialTripleShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        // 只给预处理与切片的组合，缺向量化
        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
                assertTrue(e.getMessage().contains("需一次设置全部可绑定策略"), e.getMessage());
verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 批量绑定：任一项版本为空按 40001 拒绝。
     *
     * <p>守护 {@code bindStrategies}：空版本被放行即失败。
     */
    @Test
    void bindStrategiesWithNullVersionShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        // 三件套类型齐全，但向量化没给版本 —— 会造成"部分走绑定、部分走最新启用"的隐性不一致
        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", null));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
                assertTrue(e.getMessage().contains("策略版本不能为空"), e.getMessage());
verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 批量绑定：绑定开关关闭时拒绝。
     *
     * <p>守护 {@code bindStrategies}：测评库被绑定策略即失败。
     */
    @Test
    void bindStrategiesWhenBindingDisabledShouldReject() {
        WbKnowledgeBase disabled = kb(2L, 1);
        disabled.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(disabled);

        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", 33L));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
                assertTrue(e.getMessage().contains("已关闭策略绑定"), e.getMessage());
verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 批量绑定：未知类型按 40001 拒绝。
     *
     * <p>守护 {@code bindStrategies}：白名单失效即失败。
     */
    @Test
    void bindStrategiesWithUnknownTypeShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        // 检索类策略不绑知识库（走索引版本的默认检索规则）
        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("RETRIEVAL", 44L));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
                assertTrue(e.getMessage().contains("未知策略类型"), e.getMessage());
verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 单类型绑定：开关关闭时拒绝。
     *
     * <p>守护 {@code bindStrategy}：测评库被绑定策略即失败。
     */
    @Test
    void bindStrategyWhenBindingDisabledShouldReject() {
        WbKnowledgeBase closed = kb(2L, 1);
        closed.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(closed);

        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.bindStrategy(2L, dto));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
                assertTrue(e.getMessage().contains("已关闭策略绑定"), e.getMessage());
verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 批量绑定：空列表与重复类型一律 40001。
     *
     * <p>守护 {@code bindStrategies} 的入参校验：空列表静默成功会让调用方以为绑定已生效。
     */
    @Test
    void bindStrategiesWithEmptyOrDuplicateTypeShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        StrategyBindingsUpdateDTO empty = new StrategyBindingsUpdateDTO();
        empty.setBindings(List.of());
        KnowledgeException emptyError =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, empty));
        assertEquals(ErrorCode.PARAM_INVALID, emptyError.getErrorCode());
        assertTrue(emptyError.getMessage().contains("绑定列表不能为空"), emptyError.getMessage());

        StrategyBindingsUpdateDTO duplicated = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("CHUNK", 33L));
        KnowledgeException dupError =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, duplicated));
        assertEquals(ErrorCode.PARAM_INVALID, dupError.getErrorCode());
        assertTrue(dupError.getMessage().contains("策略类型重复"), dupError.getMessage());

        verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 详情：填充切片策略绑定摘要。
     *
     * <p>守护 {@code detail}：摘要漏填即失败。
     */
    @Test
    void detailShouldFillChunkBindingSummary() {
        WbKnowledgeBase k = kb(2L, 1);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(k);
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding(66L));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());

        KnowledgeBaseVO vo = service.detail(2L);

        assertEquals(66L, vo.getChunkStrategyVersionId());
        assertEquals("chunk-hybrid-v1", vo.getChunkStrategyVersion());
    }

    /**
     * 详情：无绑定时摘要字段留空。
     *
     * <p>守护 {@code detail}：半截数据即失败。
     */
    @Test
    void detailWithoutBindingShouldLeaveSummaryEmpty() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyBindingDbService.getByKbAndType(2L, "PREPROCESS")).thenReturn(null);
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(null);

        KnowledgeBaseVO vo = service.detail(2L);

        assertNull(vo.getChunkStrategyVersionId());
        assertNull(vo.getChunkStrategyVersion());
        assertNull(vo.getPreprocessStrategyVersionId());
        assertNull(vo.getPreprocessStrategyVersion());
    }

    /**
     * 详情：填充预处理策略绑定摘要。
     *
     * <p>守护 {@code detail}：摘要漏填即失败。
     */
    @Test
    void detailShouldFillPreprocessBindingSummary() {
        WbKnowledgeBase k = kb(2L, 1);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(k);
        WbStrategyBinding preprocessBinding = new WbStrategyBinding();
        preprocessBinding.setId(2L);
        preprocessBinding.setKnowledgeBaseId(2L);
        preprocessBinding.setStrategyType("PREPROCESS");
        preprocessBinding.setStrategyVersionId(77L);
        when(strategyBindingDbService.getByKbAndType(2L, "PREPROCESS")).thenReturn(preprocessBinding);
        WbPipelineStrategyVersion preprocessVersion = new WbPipelineStrategyVersion();
        preprocessVersion.setId(77L);
        preprocessVersion.setType("PREPROCESS");
        preprocessVersion.setName("preproc-strict");
        preprocessVersion.setVersion("v2");
        when(strategyVersionDbService.getById(77L)).thenReturn(preprocessVersion);
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(null);

        KnowledgeBaseVO vo = service.detail(2L);

        assertEquals(77L, vo.getPreprocessStrategyVersionId());
        assertEquals("preproc-strict-v2", vo.getPreprocessStrategyVersion());
        assertNull(vo.getChunkStrategyVersionId());
    }

    /**
     * 详情要填满三种策略的绑定摘要，向量化那一路同样不能漏。
     *
     * <p>守护 {@code fillStrategyBindings} 的三次调用：漏掉 EMBED 会让前端显示未绑定。
     */
    @Test
    void detailShouldFillEmbedBindingSummary() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        WbStrategyBinding embedBinding = new WbStrategyBinding();
        embedBinding.setId(3L);
        embedBinding.setKnowledgeBaseId(2L);
        embedBinding.setStrategyType("EMBED");
        embedBinding.setStrategyVersionId(88L);
        when(strategyBindingDbService.getByKbAndType(2L, "EMBED")).thenReturn(embedBinding);
        WbPipelineStrategyVersion embedVersion = new WbPipelineStrategyVersion();
        embedVersion.setId(88L);
        embedVersion.setType("EMBED");
        embedVersion.setName("embed-default");
        embedVersion.setVersion("v3");
        when(strategyVersionDbService.getById(88L)).thenReturn(embedVersion);

        KnowledgeBaseVO vo = service.detail(2L);

        assertEquals(88L, vo.getEmbedStrategyVersionId());
        assertEquals("embed-default-v3", vo.getEmbedStrategyVersion());
    }

    /**
     * 分页时三种策略的绑定摘要都按批量方式回填，缺版本的行留空。
     *
     * <p>守护 {@code fillStrategyBindings(records)}/{@code fillBindingForType}：只填一种类型、
     * 或把失效版本硬填成半截数据即失败。
     */
    @Test
    void pageShouldFillBindingsForEachTypeAndSkipMissingVersion() {
        Page<WbKnowledgeBase> page = kbPage(kb(7L, 1), kb(8L, 1), kb(9L, 1));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, KnowledgeBaseSort.DEFAULT, ME))
                .thenReturn(page);
        when(kbFileResultDbService.countGroupByKb(List.of(7L, 8L, 9L))).thenReturn(Map.of(7L, 2L));
        when(strategyBindingDbService.listActiveByTypeAndKbIds("PREPROCESS", List.of(7L, 8L, 9L)))
                .thenReturn(List.of(bindingOf(7L, "PREPROCESS", 11L)));
        when(strategyBindingDbService.listActiveByTypeAndKbIds("CHUNK", List.of(7L, 8L, 9L)))
                .thenReturn(List.of(bindingOf(7L, "CHUNK", 22L), bindingOf(9L, "CHUNK", 999L)));
        when(strategyBindingDbService.listActiveByTypeAndKbIds("EMBED", List.of(7L, 8L, 9L)))
                .thenReturn(List.of(bindingOf(7L, "EMBED", 33L)));
        when(strategyVersionDbService.listByIds(List.of(11L)))
                .thenReturn(List.of(versionOf(11L, "PREPROCESS", "preproc-strict", "v2")));
        when(strategyVersionDbService.listByIds(List.of(22L, 999L)))
                .thenReturn(List.of(versionOf(22L, "CHUNK", "chunk-hybrid", "v1")));
        when(strategyVersionDbService.listByIds(List.of(33L)))
                .thenReturn(List.of(versionOf(33L, "EMBED", "embed-default", "v1")));

        List<KnowledgeBaseVO> records = service.page(kbQuery(null, null, null)).getRecords();

        KnowledgeBaseVO bound = records.get(0);
        assertEquals(11L, bound.getPreprocessStrategyVersionId());
        assertEquals("preproc-strict-v2", bound.getPreprocessStrategyVersion());
        assertEquals(22L, bound.getChunkStrategyVersionId());
        assertEquals("chunk-hybrid-v1", bound.getChunkStrategyVersion());
        assertEquals(33L, bound.getEmbedStrategyVersionId());
        assertEquals("embed-default-v1", bound.getEmbedStrategyVersion());
        assertEquals(2L, bound.getDocumentCount());
        // 无绑定的库留空
        assertNull(records.get(1).getChunkStrategyVersionId());
        assertNull(records.get(1).getEmbedStrategyVersionId());
        // 绑定指向已失效版本：留空而不是半截数据
        assertNull(records.get(2).getChunkStrategyVersionId());
        assertNull(records.get(2).getChunkStrategyVersion());
    }

    /**
     * 分页没有绑定行时不再去查策略版本。
     *
     * <p>守护 {@code fillBindingForType} 的空集短路：多打一次 {@code listByIds(空)} 是白查询。
     */
    @Test
    void pageWithoutBindingsShouldNotLoadVersions() {
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, KnowledgeBaseSort.DEFAULT, ME))
                .thenReturn(kbPage(kb(7L, 1)));
        when(kbFileResultDbService.countGroupByKb(List.of(7L))).thenReturn(Map.of());
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());

        KnowledgeBaseVO vo = service.page(kbQuery(null, null, null)).getRecords().getFirst();

        assertNull(vo.getChunkStrategyVersionId());
        verify(strategyVersionDbService, never()).listByIds(any());
    }

    /**
     * 提交记录分页：按行转换成 VO，失败原因随行返回。
     *
     * <p>守护 {@code pageSubmitLogs} 的转换链路：直接返回实体或丢字段即失败。
     */
    @Test
    void pageSubmitLogsShouldConvertRows() {
        com.workbuddy.common.domain.entity.WbSubmitLog fail = new com.workbuddy.common.domain.entity.WbSubmitLog();
        fail.setId(30L);
        fail.setKnowledgeBaseId(1L);
        fail.setRequestId("req-1");
        fail.setFileId("88");
        fail.setFileName("坏文件.exe");
        fail.setStatus("FAIL");
        fail.setFailReason("FORMAT_NOT_ALLOWED");
        Page<com.workbuddy.common.domain.entity.WbSubmitLog> page = new Page<>(1, 10);
        page.setRecords(List.of(fail));
        when(submitLogDbService.pageByKb(1L, 10L, 1L, "FAIL", "坏文件")).thenReturn(page);
        com.workbuddy.common.dto.request.knowledge.SubmitLogQueryDTO query =
                new com.workbuddy.common.dto.request.knowledge.SubmitLogQueryDTO();
        query.setStatus("FAIL");
        query.setFileName("坏文件");

        List<com.workbuddy.common.dto.response.input.SubmitLogVO> records =
                service.pageSubmitLogs(1L, query).getRecords();

        assertEquals(1, records.size());
        assertEquals(30L, records.getFirst().getId());
        assertEquals("req-1", records.getFirst().getRequestId());
        assertEquals("坏文件.exe", records.getFirst().getFileName());
        assertEquals("FAIL", records.getFirst().getStatus());
        assertEquals("FORMAT_NOT_ALLOWED", records.getFirst().getFailReason());
    }

    /** 分页结果夹具（单页、无总数） */
    private Page<WbKnowledgeBase> kbPage(WbKnowledgeBase... rows) {
        Page<WbKnowledgeBase> page = new Page<>(1, 10);
        page.setRecords(List.of(rows));
        page.setTotal(rows.length);
        return page;
    }

    /** 绑定行夹具（指定库与类型） */
    private WbStrategyBinding bindingOf(Long kbId, String type, Long versionId) {
        WbStrategyBinding binding = new WbStrategyBinding();
        binding.setKnowledgeBaseId(kbId);
        binding.setStrategyType(type);
        binding.setStrategyVersionId(versionId);
        return binding;
    }

    /** 策略版本行夹具 */
    private WbPipelineStrategyVersion versionOf(Long id, String type, String name, String version) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(id);
        row.setType(type);
        row.setName(name);
        row.setVersion(version);
        row.setStatus("ACTIVE");
        return row;
    }

    // ---------------- 归属可见范围 ----------------
    //
    // 口径（见 KnowledgeBaseRules）：管理员不限归属，普通用户只能读写自己创建的库。
    // 越权一律按「不存在」（KB_NOT_FOUND 40401）处理 —— 不泄露"这个库存在，只是不是你的"。

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

        service.create(dto);

        ArgumentCaptor<WbKnowledgeBase> cap = ArgumentCaptor.forClass(WbKnowledgeBase.class);
        verify(knowledgeBaseDbService).save(cap.capture());
        assertEquals(Long.valueOf(ME), cap.getValue().getUserId(),
                "知识库必须带归属：user_id 为空的行普通用户谁也看不见");

        // 无登录上下文：拒绝，而不是静默落一条 user_id = NULL 的孤儿数据
        SecurityContextHolder.clearContext();
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.create(dto));
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

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.detail(2L));

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

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.detail(9L));

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

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.detail(9L));

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

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.update(dto));

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

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.disable(4L));

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

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.delete(6L));

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
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));

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
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, KnowledgeBaseSort.DEFAULT, null)).thenReturn(page);
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        assertEquals(9L, service.detail(9L).getId(), "管理员可读别人的库");

        service.page(kbQuery(null, null, null));

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
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(knowledgeBaseDbService.countByStatus(null, ME)).thenReturn(2L);
        when(knowledgeBaseDbService.countByStatus(1, ME)).thenReturn(1L);
        when(knowledgeBaseDbService.listIdsByOwner(ME)).thenReturn(List.of(7L, 8L));
        when(kbFileResultDbService.countByKbIds(List.of(7L, 8L))).thenReturn(9L);

        service.page(kbQuery(null, null, null));
        KnowledgeBaseStatsVO stats = service.stats();

        // 列表与计数都按当前用户归属收口；文档数也取同一范围（不是全平台 countAll）
        verify(knowledgeBaseDbService).pageByCondition(1L, 10L, null, null, KnowledgeBaseSort.DEFAULT, ME);
        // 空页不再去打绑定/版本这类批量查询
        verify(strategyBindingDbService, never()).listActiveByTypeAndKbIds(any(), any());
        assertEquals(2L, stats.getKnowledgeBaseCount());
        assertEquals(1L, stats.getEnabledCount());
        assertEquals(9L, stats.getDocumentCount());
    }

    /** 分页查询条件（页码 1 / 页长 10 由缺省口径兜底，名称 / 状态 / 排序可空） */
    private KnowledgeBaseQueryDTO kbQuery(String name, Integer status, String sort) {
        KnowledgeBaseQueryDTO query = new KnowledgeBaseQueryDTO();
        query.setName(name);
        query.setStatus(status);
        query.setSort(sort);
        return query;
    }
}
