package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.workbuddy.biz.service.db.KbAuditLogDbService;
import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KbIndexSetDbService;
import com.workbuddy.biz.service.db.KbIndexVersionDbService;
import com.workbuddy.biz.service.db.KbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.service.db.KbSourceFileDbService;
import com.workbuddy.biz.service.db.KbStrategyBindingDbService;
import com.workbuddy.biz.service.db.KbSubmitLogDbService;
import com.workbuddy.biz.service.db.KnowledgeBaseDbService;
import com.workbuddy.biz.service.support.InputVoAssembler;
import com.workbuddy.biz.service.support.TaskVoAssembler;
import com.workbuddy.common.domain.entity.KbIndexSet;
import com.workbuddy.common.domain.entity.KbIndexVersion;
import com.workbuddy.common.domain.entity.KbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.KbStrategyBinding;
import com.workbuddy.common.domain.entity.KnowledgeBase;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDto;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDto;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingUpdateDto;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateRequest;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileStorage;
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
 *
 * @author cxxl
 */
class KnowledgeBaseServiceImplTest {

    /** 当前登录用户（普通用户）：夹具知识库默认都归它，正向用例才能过归属校验 */
    private static final long ME = 1001L;

    /** 另一个用户：用于「看不到别人的库」的反向用例 */
    private static final long OTHER = 2002L;

    private KnowledgeBaseDbService knowledgeBaseDbService;

    private KbAuditLogDbService kbAuditLogDbService;

    private KbStrategyBindingDbService strategyBindingDbService;

    private KbPipelineStrategyVersionDbService strategyVersionDbService;

    private KbFileResultDbService kbFileResultDbService;

    private KbIndexSetDbService indexSetDbService;

    private KbIndexVersionDbService indexVersionDbService;

    private KbSourceFileDbService sourceFileDbService;

    private KbSubmitLogDbService submitLogDbService;

    private KbPipelineTaskDbService pipelineTaskDbService;

    private FileValidatorPort fileValidator;

    private FileStorage fileStorage;

    private StorageRouter storageRouter;

    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        knowledgeBaseDbService = mock(KnowledgeBaseDbService.class);
        kbAuditLogDbService = mock(KbAuditLogDbService.class);
        strategyBindingDbService = mock(KbStrategyBindingDbService.class);
        strategyVersionDbService = mock(KbPipelineStrategyVersionDbService.class);
        kbFileResultDbService = mock(KbFileResultDbService.class);
        indexSetDbService = mock(KbIndexSetDbService.class);
        indexVersionDbService = mock(KbIndexVersionDbService.class);
        sourceFileDbService = mock(KbSourceFileDbService.class);
        submitLogDbService = mock(KbSubmitLogDbService.class);
        pipelineTaskDbService = mock(KbPipelineTaskDbService.class);
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

    private KnowledgeBase kb(long id, int status) {
        KnowledgeBase k = new KnowledgeBase();
        k.setId(id);
        k.setName("库" + id);
        k.setStatus(status);
        // 归属：本夹具统一归当前登录用户，反向用例另行 setUserId(OTHER)
        k.setUserId(ME);
        return k;
    }

    /** 有效绑定行（KB 2 绑定 CHUNK） */
    private KbStrategyBinding binding(Long versionId) {
        KbStrategyBinding binding = new KbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(2L);
        binding.setStrategyType("CHUNK");
        binding.setStrategyVersionId(versionId);
        return binding;
    }

    /** 启用中的 CHUNK 策略版本行 */
    private KbPipelineStrategyVersion chunkVersion() {
        KbPipelineStrategyVersion row = new KbPipelineStrategyVersion();
        row.setId(66L);
        row.setType("CHUNK");
        row.setName("chunk-hybrid");
        row.setVersion("v1");
        row.setStatus("ACTIVE");
        return row;
    }

    @Test
    void createShouldSaveActiveAndAuditCreate() {
        KnowledgeBaseCreateDto dto = new KnowledgeBaseCreateDto();
        dto.setName("招投标知识库");
        dto.setDescription("招投标场景");
        doAnswer(inv -> {
            KnowledgeBase k = inv.getArgument(0);
            k.setId(1L);
            return true;
        }).when(knowledgeBaseDbService).save(any(KnowledgeBase.class));

        Long id = service.create(dto);

        assertEquals(1L, id);
        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseDbService).save(kbCaptor.capture());
        assertEquals(KnowledgeBaseStatus.ACTIVE.getCode(), kbCaptor.getValue().getStatus());
        assertEquals("招投标知识库", kbCaptor.getValue().getName());

        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.CREATE), eq("KNOWLEDGE_BASE"), eq(1L), isNull(), anyString());
    }

    @Test
    void detailWhenAbsentShouldThrowNotFound() {
        when(knowledgeBaseDbService.getActiveById(9L)).thenThrow(new KnowledgeException(ErrorCode.KB_NOT_FOUND));
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.detail(9L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void detailShouldMapToVO() {
        KnowledgeBase k = kb(2L, 1);
        k.setDescription("描述");
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(k);

        KnowledgeBaseVO vo = service.detail(2L);

        assertEquals(2L, vo.getId());
        assertEquals("库2", vo.getName());
        assertEquals("描述", vo.getDescription());
        assertEquals(1, vo.getStatus());
    }

    @Test
    void updateShouldChangeNameAndAudit() {
        KnowledgeBase k = kb(3L, 1);
        when(knowledgeBaseDbService.getActiveById(3L)).thenReturn(k);
        KnowledgeBaseUpdateDto dto = new KnowledgeBaseUpdateDto();
        dto.setId(3L);
        dto.setName("新名称");
        dto.setDescription("新描述");

        service.update(dto);

        ArgumentCaptor<KnowledgeBase> cap = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseDbService).updateById(cap.capture());
        assertEquals("新名称", cap.getValue().getName());
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.UPDATE), eq("KNOWLEDGE_BASE"), eq(3L), anyString(), anyString());
    }

    @Test
    void disableOnActiveShouldUpdateStatusAndAudit() {
        when(knowledgeBaseDbService.getActiveById(4L)).thenReturn(kb(4L, 1));

        service.disable(4L);

        ArgumentCaptor<KnowledgeBase> cap = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseDbService).updateById(cap.capture());
        assertEquals(0, cap.getValue().getStatus());
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.DISABLE), eq("KNOWLEDGE_BASE"), eq(4L), anyString(), anyString());
    }

    @Test
    void disableOnDisabledShouldThrowAndNotUpdate() {
        when(knowledgeBaseDbService.getActiveById(4L)).thenReturn(kb(4L, 0));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.disable(4L));

        assertEquals(ErrorCode.KB_STATUS_ILLEGAL, e.getErrorCode());
        verify(knowledgeBaseDbService, never()).updateById(any());
    }

    @Test
    void enableOnDisabledShouldUpdateStatus() {
        when(knowledgeBaseDbService.getActiveById(5L)).thenReturn(kb(5L, 0));

        service.enable(5L);

        ArgumentCaptor<KnowledgeBase> cap = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseDbService).updateById(cap.capture());
        assertEquals(1, cap.getValue().getStatus());
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.ENABLE), eq("KNOWLEDGE_BASE"), eq(5L), anyString(), anyString());
    }

    @Test
    void deleteShouldLogicDeleteAndAudit() {
        when(knowledgeBaseDbService.getActiveById(6L)).thenReturn(kb(6L, 1));

        service.delete(6L);

        verify(knowledgeBaseDbService).removeById(6L);
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.DELETE), eq("KNOWLEDGE_BASE"), eq(6L), anyString(), isNull());
    }

    @Test
    void pageShouldOrderNewestFirst() {
        // 直接验证查询条件构造：Lambda 必须解析成正确的列名与排序方向。
        // 若 lambda 引用写错（例如误用 name），解析出来会是别的列名而不报错，属于静默 bug。
        // 注意：脱离 Spring 上下文时 MPJ 没有 TableInfo 缓存，需先初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeBase.class);
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(false, KnowledgeBase::getName, null)
                .eq(false, KnowledgeBase::getStatus, null)
                // 归属条件必须落在 user_id 上：Lambda 引用写错（例如误用 create_by）不报错，
                // 只是静默变成"按别的列过滤"，那等于归属隔离失效
                .eq(true, KnowledgeBase::getUserId, ME)
                .orderByDesc(KnowledgeBase::getId);
        String sql = wrapper.getSqlSegment();

        assertTrue(sql.contains("user_id ="), "归属过滤应落在 user_id 列，实际: " + sql);
        assertTrue(sql.contains("id DESC"), "应按 id 倒序（新建的在前），实际: " + sql);
    }

    @Test
    void pageShouldFillPublishedIndexVersionWithBatchQueries() {
        KnowledgeBase withIndex = kb(7L, 1);
        withIndex.setPublishedIndexSetId(700L);
        KnowledgeBase withoutIndex = kb(8L, 1);
        Page<KnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(2);
        page.setRecords(List.of(withIndex, withoutIndex));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, null, ME)).thenReturn(page);
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        KbIndexSet set = new KbIndexSet();
        set.setKnowledgeBaseId(7L);
        set.setCurrentPublishedVersionId(900L);
        when(indexSetDbService.listByKbIds(any())).thenReturn(Map.of(7L, set));
        KbIndexVersion version = new KbIndexVersion();
        version.setId(900L);
        version.setVersionNo("v3");
        when(indexVersionDbService.listByIds(any())).thenReturn(List.of(version));

        IPage<KnowledgeBaseVO> result = service.page(1, 10, null, null, null);

        assertEquals("v3", result.getRecords().get(0).getPublishedIndexVersion());
        // 未发布索引的库为 null（前端据此显示「未发布」）
        assertNull(result.getRecords().get(1).getPublishedIndexVersion());
        // N+1 防护：整页只允许一次集合查询 + 一次版本查询
        verify(indexSetDbService).listByKbIds(any());
        verify(indexVersionDbService).listByIds(any());
    }

    @Test
    void pageShouldSkipIndexLookupWhenNobodyPublished() {
        Page<KnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(kb(7L, 1)));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, null, ME)).thenReturn(page);
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        service.page(1, 10, null, null, null);

        // 没有任何库发布过索引时不应产生多余的查询
        verify(indexSetDbService, never()).listByKbIds(any());
        verify(indexVersionDbService, never()).listByIds(any());
    }

    @Test
    void pageShouldReturnMappedRecords() {

        Page<KnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(kb(7L, 1)));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, "库7", null, null, ME)).thenReturn(page);
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of(7L, 3L));

        IPage<KnowledgeBaseVO> result = service.page(1, 10, "库7", null, null);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("库7", result.getRecords().getFirst().getName());
        // 文档数按 kb_file_result 记录数回填；无记录的库补 0 而非 null
        assertEquals(3L, result.getRecords().getFirst().getDocumentCount());
    }

    @Test
    void pageShouldPassStatusFilterAndDefaultDocumentCountToZero() {
        Page<KnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(kb(8L, 0)));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, 0, null, ME)).thenReturn(page);
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        IPage<KnowledgeBaseVO> result = service.page(1, 10, null, 0, null);

        assertEquals(0L, result.getRecords().getFirst().getDocumentCount());
    }

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

    @Test
    void strategyBindingShouldReturnBoundVersion() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding(66L));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());

        StrategyBindingVO vo = service.strategyBinding(2L, "CHUNK");

        assertEquals(66L, vo.getStrategyVersionId());
        assertEquals("chunk-hybrid", vo.getStrategyName());
        assertEquals("v1", vo.getStrategyVersion());
    }

    @Test
    void strategyBindingWithUnknownTypeShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.strategyBinding(2L, "INDEX"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    @Test
    void bindStrategyShouldReuseExistingRow() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());
        KbStrategyBinding existing = binding(55L);
        existing.setDelFlag(DelFlag.DELETED.getCode());
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(existing);

        StrategyBindingUpdateDto dto = new StrategyBindingUpdateDto();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        assertTrue(service.bindStrategy(2L, dto));

        assertEquals(DelFlag.NORMAL.getCode(), existing.getDelFlag());
        assertEquals(66L, existing.getStrategyVersionId());
        verify(strategyBindingDbService).updateById(existing);
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.BIND), eq("KNOWLEDGE_BASE"), eq(2L), anyString(), anyString());
    }

    @Test
    void bindStrategyWithNullVersionShouldUnbind() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding(66L));

        StrategyBindingUpdateDto dto = new StrategyBindingUpdateDto();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(null);

        assertTrue(service.bindStrategy(2L, dto));

        ArgumentCaptor<KbStrategyBinding> captor = ArgumentCaptor.forClass(KbStrategyBinding.class);
        verify(strategyBindingDbService).updateById(captor.capture());
        assertEquals(DelFlag.DELETED.getCode(), captor.getValue().getDelFlag());
        verify(kbAuditLogDbService).saveAudit(
                eq(AuditActionType.BIND), eq("KNOWLEDGE_BASE"), eq(2L), anyString(), isNull());
    }

    @Test
    void bindStrategyWithInactiveVersionShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        KbPipelineStrategyVersion inactive = chunkVersion();
        inactive.setStatus("INACTIVE");
        when(strategyVersionDbService.getById(66L)).thenReturn(inactive);

        StrategyBindingUpdateDto dto = new StrategyBindingUpdateDto();
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
    private StrategyBindingsUpdateRequest.StrategyBindItem bindItem(String type, Long versionId) {
        StrategyBindingsUpdateRequest.StrategyBindItem item =
                new StrategyBindingsUpdateRequest.StrategyBindItem();
        item.setStrategyType(type);
        item.setStrategyVersionId(versionId);
        return item;
    }

    /** 批量绑定请求（绑定项可变参数） */
    private StrategyBindingsUpdateRequest bindRequest(
            StrategyBindingsUpdateRequest.StrategyBindItem... items) {
        StrategyBindingsUpdateRequest request = new StrategyBindingsUpdateRequest();
        request.setBindings(List.of(items));
        return request;
    }

    /** 让三件套的版本查询都返回启用中的行（类型与入参一致） */
    private void stubActiveVersions(String type, Long versionId) {
        KbPipelineStrategyVersion row = new KbPipelineStrategyVersion();
        row.setId(versionId);
        row.setType(type);
        row.setName(type.toLowerCase() + "-x");
        row.setVersion("v1");
        row.setStatus("ACTIVE");
        when(strategyVersionDbService.getById(versionId)).thenReturn(row);
    }

    @Test
    void bindStrategiesShouldAcceptFullTriple() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        stubActiveVersions("PREPROCESS", 11L);
        stubActiveVersions("CHUNK", 22L);
        stubActiveVersions("EMBED", 33L);
        when(strategyBindingDbService.getAnyByKbAndType(eq(2L), anyString())).thenReturn(null);

        StrategyBindingsUpdateRequest request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", 33L));

        assertTrue(service.bindStrategies(2L, request));

        verify(strategyBindingDbService, times(3)).save(any(KbStrategyBinding.class));
    }

    @Test
    void bindStrategiesWithPartialTripleShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        // 只给预处理与切片的组合，缺向量化
        StrategyBindingsUpdateRequest request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyBindingDbService, never()).save(any());
    }

    @Test
    void bindStrategiesWithNullVersionShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        // 三件套类型齐全，但向量化没给版本 —— 会造成"部分走绑定、部分走最新启用"的隐性不一致
        StrategyBindingsUpdateRequest request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", null));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyBindingDbService, never()).save(any());
    }

    @Test
    void bindStrategiesWhenBindingDisabledShouldReject() {
        KnowledgeBase disabled = kb(2L, 1);
        disabled.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(disabled);

        StrategyBindingsUpdateRequest request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", 33L));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyBindingDbService, never()).save(any());
    }

    @Test
    void bindStrategiesWithUnknownTypeShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        // 检索类策略不绑知识库（走索引版本的默认检索规则）
        StrategyBindingsUpdateRequest request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("RETRIEVAL", 44L));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyBindingDbService, never()).save(any());
    }

    @Test
    void bindStrategyWhenBindingDisabledShouldReject() {
        KnowledgeBase closed = kb(2L, 1);
        closed.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(closed);

        StrategyBindingUpdateDto dto = new StrategyBindingUpdateDto();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.bindStrategy(2L, dto));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyBindingDbService, never()).save(any());
    }

    @Test
    void detailShouldFillChunkBindingSummary() {
        KnowledgeBase k = kb(2L, 1);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(k);
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding(66L));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());

        KnowledgeBaseVO vo = service.detail(2L);

        assertEquals(66L, vo.getChunkStrategyVersionId());
        assertEquals("chunk-hybrid-v1", vo.getChunkStrategyVersion());
    }

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

    @Test
    void detailShouldFillPreprocessBindingSummary() {
        KnowledgeBase k = kb(2L, 1);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(k);
        KbStrategyBinding preprocessBinding = new KbStrategyBinding();
        preprocessBinding.setId(2L);
        preprocessBinding.setKnowledgeBaseId(2L);
        preprocessBinding.setStrategyType("PREPROCESS");
        preprocessBinding.setStrategyVersionId(77L);
        when(strategyBindingDbService.getByKbAndType(2L, "PREPROCESS")).thenReturn(preprocessBinding);
        KbPipelineStrategyVersion preprocessVersion = new KbPipelineStrategyVersion();
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

    // ---------------- 归属可见范围 ----------------
    //
    // 口径（见 KnowledgeBaseRules）：管理员不限归属，普通用户只能读写自己创建的库。
    // 越权一律按「不存在」（KB_NOT_FOUND 40401）处理 —— 不泄露"这个库存在，只是不是你的"。

    @Test
    void createShouldStampOwnerAndRejectWithoutLogin() {
        KnowledgeBaseCreateDto dto = new KnowledgeBaseCreateDto();
        dto.setName("我的库");
        doAnswer(inv -> {
            inv.getArgument(0, KnowledgeBase.class).setId(1L);
            return true;
        }).when(knowledgeBaseDbService).save(any(KnowledgeBase.class));

        service.create(dto);

        ArgumentCaptor<KnowledgeBase> cap = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseDbService).save(cap.capture());
        assertEquals(Long.valueOf(ME), cap.getValue().getUserId(),
                "知识库必须带归属：user_id 为空的行普通用户谁也看不见");

        // 无登录上下文：拒绝，而不是静默落一条 user_id = NULL 的孤儿数据
        SecurityContextHolder.clearContext();
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.create(dto));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    @Test
    void detailWithoutLoginShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        SecurityContextHolder.clearContext();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.detail(2L));

        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    @Test
    void detailOnOthersKbShouldBehaveAsNotFound() {
        KnowledgeBase others = kb(9L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(9L)).thenReturn(others);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.detail(9L));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void detailOnLegacyKbWithoutOwnerShouldBeInvisibleToNormalUser() {
        // 存量 user_id = NULL 的早期数据：普通用户不可见（不是公共资产）
        KnowledgeBase legacy = kb(9L, 1);
        legacy.setUserId(null);
        when(knowledgeBaseDbService.getActiveById(9L)).thenReturn(legacy);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.detail(9L));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void updateOnOthersKbShouldThrowAndNotUpdate() {
        KnowledgeBase others = kb(3L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(3L)).thenReturn(others);
        KnowledgeBaseUpdateDto dto = new KnowledgeBaseUpdateDto();
        dto.setId(3L);
        dto.setName("改别人的库");

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.update(dto));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(knowledgeBaseDbService, never()).updateById(any());
    }

    @Test
    void disableOnOthersKbShouldThrowAndNotUpdate() {
        KnowledgeBase others = kb(4L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(4L)).thenReturn(others);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.disable(4L));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(knowledgeBaseDbService, never()).updateById(any());
    }

    @Test
    void deleteOnOthersKbShouldThrowAndNotRemove() {
        KnowledgeBase others = kb(6L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(6L)).thenReturn(others);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.delete(6L));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(knowledgeBaseDbService, never()).removeById(any());
    }

    @Test
    void bindStrategiesOnOthersKbShouldThrowAndNotSave() {
        KnowledgeBase others = kb(2L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(others);
        StrategyBindingsUpdateRequest request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", 33L));

        KnowledgeException e =
                assertThrows(KnowledgeException.class, () -> service.bindStrategies(2L, request));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(strategyBindingDbService, never()).save(any());
    }

    @Test
    void adminShouldAccessOthersKbAndSkipOwnerFilter() {
        login(ME, UserRole.ADMIN);
        KnowledgeBase others = kb(9L, 1);
        others.setUserId(OTHER);
        when(knowledgeBaseDbService.getActiveById(9L)).thenReturn(others);
        Page<KnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(kb(7L, 1)));
        // 管理员：归属条件为 null（不加 user_id 过滤）
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, null, null)).thenReturn(page);
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());
        when(kbFileResultDbService.countGroupByKb(any())).thenReturn(Map.of());

        assertEquals(9L, service.detail(9L).getId(), "管理员可读别人的库");

        service.page(1, 10, null, null, null);

        verify(knowledgeBaseDbService).pageByCondition(1L, 10L, null, null, null, null);
    }

    @Test
    void pageAndStatsShouldScopeToCurrentUser() {
        Page<KnowledgeBase> page = new Page<>(1, 10);
        page.setTotal(0);
        page.setRecords(List.of());
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null, null, ME)).thenReturn(page);
        when(knowledgeBaseDbService.countByStatus(null, ME)).thenReturn(2L);
        when(knowledgeBaseDbService.countByStatus(1, ME)).thenReturn(1L);
        when(knowledgeBaseDbService.listIdsByOwner(ME)).thenReturn(List.of(7L, 8L));
        when(kbFileResultDbService.countByKbIds(List.of(7L, 8L))).thenReturn(9L);

        service.page(1, 10, null, null, null);
        KnowledgeBaseStatsVO stats = service.stats();

        // 列表与计数都按当前用户归属收口；文档数也取同一范围（不是全平台 countAll）
        verify(knowledgeBaseDbService).pageByCondition(1L, 10L, null, null, null, ME);
        assertEquals(2L, stats.getKnowledgeBaseCount());
        assertEquals(1L, stats.getEnabledCount());
        assertEquals(9L, stats.getDocumentCount());
    }
}
