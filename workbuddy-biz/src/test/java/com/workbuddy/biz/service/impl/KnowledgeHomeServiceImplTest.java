package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.dto.request.home.HomeRecentSubmitQueryDTO;
import com.workbuddy.common.dto.response.home.HomeSummaryVO;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.strategy.StrategyType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.SecurityUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 首页服务单测。
 *
 * <p>守护三条契约：速览的可见范围（普通用户只统计自己的库）、
 * 最近提交只查当前登录用户（不接受「提交人」参数）、
 * 知识库名与文件类型批量反查（缺失回落占位，不逐行查库）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeHomeServiceImplTest {

    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    @Mock
    private WbPipelineStrategyVersionDbService strategyVersionDbService;

    @Mock
    private WbFileResultDbService fileResultDbService;

    @Mock
    private WbSubmitLogDbService submitLogDbService;

    @Mock
    private WbSourceFileDbService sourceFileDbService;

    private MockedStatic<SecurityUtil> security;

    private KnowledgeHomeServiceImpl service;

    /**
     * 每个用例重建静态登录上下文（普通用户 ID=9）。
     */
    @BeforeEach
    void setUp() {
        security = Mockito.mockStatic(SecurityUtil.class);
        security.when(SecurityUtil::getUser).thenReturn(user(9L));
        service = new KnowledgeHomeServiceImpl(knowledgeBaseDbService, strategyVersionDbService,
                fileResultDbService, submitLogDbService, sourceFileDbService);
    }

    /**
     * 收尾关闭静态桩。
     */
    @AfterEach
    void tearDown() {
        security.close();
    }

    /**
     * 速览：知识库计数按可见范围、四类策略版本分别计数、文档数按可见库集合。
     *
     * <p>守护 {@code summary} 的计数装配：漏一类策略或文档口径错位即失败。
     */
    @Test
    void summaryShouldAggregateCountsByVisibleScope() {
        when(knowledgeBaseDbService.countByStatus(isNull(), eq(9L))).thenReturn(3L);
        when(knowledgeBaseDbService.countByStatus(eq(KnowledgeBaseStatus.ACTIVE.getCode()), eq(9L)))
                .thenReturn(2L);
        when(strategyVersionDbService.countEnabledByType(StrategyType.PREPROCESS)).thenReturn(1L);
        when(strategyVersionDbService.countEnabledByType(StrategyType.CHUNK)).thenReturn(2L);
        when(strategyVersionDbService.countEnabledByType(StrategyType.EMBED)).thenReturn(3L);
        when(strategyVersionDbService.countEnabledByType(StrategyType.RETRIEVAL)).thenReturn(4L);
        when(knowledgeBaseDbService.listIdsByOwner(9L)).thenReturn(List.of(1L, 2L));
        when(fileResultDbService.countByKbIds(List.of(1L, 2L))).thenReturn(12L);

        HomeSummaryVO summary = service.summary();

        assertEquals(3L, summary.getKnowledgeBaseCount());
        assertEquals(2L, summary.getEnabledKnowledgeBaseCount());
        assertEquals(1L, summary.getPreprocessVersionCount());
        assertEquals(2L, summary.getChunkVersionCount());
        assertEquals(3L, summary.getEmbedVersionCount());
        assertEquals(4L, summary.getRetrievalVersionCount());
        assertEquals(10L, summary.getStrategyVersionCount());
        assertEquals(12L, summary.getDocumentCount());
    }

    /**
     * 管理员的速览统计全库（可见范围为 null）。
     *
     * <p>守护 {@code summary} 的管理员口径：按用户过滤即失败。
     */
    @Test
    void summaryShouldCountAllForAdmin() {
        security.when(SecurityUtil::getUser).thenReturn(user(1L, com.workbuddy.common.enums.user.UserRole.ADMIN));
        when(knowledgeBaseDbService.countByStatus(isNull(), isNull())).thenReturn(5L);
        when(knowledgeBaseDbService.countByStatus(eq(KnowledgeBaseStatus.ACTIVE.getCode()), isNull()))
                .thenReturn(4L);
        when(knowledgeBaseDbService.listIdsByOwner(isNull())).thenReturn(List.of(1L, 2L, 3L));
        when(fileResultDbService.countByKbIds(List.of(1L, 2L, 3L))).thenReturn(30L);

        HomeSummaryVO summary = service.summary();

        assertEquals(5L, summary.getKnowledgeBaseCount());
        assertEquals(30L, summary.getDocumentCount());
    }

    /**
     * 安全上下文缺失时最近提交直接拒绝，不放全量。
     *
     * <p>守护 {@code recentSubmits} 的认证闸门（错误码 40101）。
     */
    @Test
    void recentSubmitsShouldRejectMissingLoginContext() {
        security.when(SecurityUtil::getUser).thenReturn(null);

        WorkbuddyException ex = assertThrows(WorkbuddyException.class,
                () -> service.recentSubmits(new HomeRecentSubmitQueryDTO()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    /**
     * 空页直接返回，不做批量反查。
     *
     * <p>守护 {@code recentSubmits} 的空页短路：空页还查库名即失败。
     */
    @Test
    void recentSubmitsShouldReturnEmptyPageWithoutLookups() {
        Page<WbSubmitLog> page = new Page<>(1, 10, 0);
        page.setRecords(List.of());
        when(submitLogDbService.pageRecentByUser(1L, 10L, 9L)).thenReturn(page);

        IPage<com.workbuddy.common.dto.response.home.HomeRecentSubmitVO> result =
                service.recentSubmits(query(1L, 10L));

        assertEquals(0, result.getRecords().size());
        verifyNoSourceLookups();
    }

    /**
     * 有记录时批量反查：知识库名缺失回落占位、文件类型由 MIME 换算、失败原因翻中文。
     *
     * <p>守护 {@code toRecentSubmitVO} 的装配与回落：库名拼错、类型解析错、
     * 失败原因没翻中文即失败。
     */
    @Test
    void recentSubmitsShouldAssembleRowsWithLookups() {
        WbSubmitLog hit = submitRow(11L, "招标文件.pdf", 1L, "file-1", "SUCCESS", null);
        WbSubmitLog failed = submitRow(12L, "损坏文件.docx", 99L, "file-2", "FAIL", "FILE_TOO_LARGE");
        WbSubmitLog noKb = submitRow(13L, "遗留记录.txt", null, "  ", "FAIL", null);
        Page<WbSubmitLog> page = new Page<>(1, 10, 3);
        page.setRecords(List.of(hit, failed, noKb));
        when(submitLogDbService.pageRecentByUser(1L, 10L, 9L)).thenReturn(page);

        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setName("标书库");
        when(knowledgeBaseDbService.listByIds(any())).thenReturn(List.of(kb));
        WbSourceFile source = new WbSourceFile();
        source.setFileId("file-1");
        source.setMimeType("application/pdf");
        when(sourceFileDbService.listByFileIds(any())).thenReturn(List.of(source));

        IPage<com.workbuddy.common.dto.response.home.HomeRecentSubmitVO> result =
                service.recentSubmits(query(1L, 10L));

        // 空知识库 ID 与空白文件 ID 不进批量反查（否则会拿 null/空白去查库）
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<Long>> kbIds =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        org.mockito.Mockito.verify(knowledgeBaseDbService).listByIds(kbIds.capture());
        assertEquals(java.util.Set.of(1L, 99L), new java.util.HashSet<>(kbIds.getValue()));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<String>> fileIds =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        org.mockito.Mockito.verify(sourceFileDbService).listByFileIds(fileIds.capture());
        assertEquals(java.util.Set.of("file-1", "file-2"), new java.util.HashSet<>(fileIds.getValue()));

        assertEquals(3, result.getRecords().size());
        assertEquals(11L, result.getRecords().get(0).getId());
        assertEquals("招标文件.pdf", result.getRecords().get(0).getFileName());
        assertEquals(1L, result.getRecords().get(0).getKnowledgeBaseId());
        assertEquals("SUCCESS", result.getRecords().get(0).getStatus());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 9, 10, 0), result.getRecords().get(0).getCreateTime());
        assertEquals("标书库", result.getRecords().get(0).getKnowledgeBaseName());
        assertEquals("PDF", result.getRecords().get(0).getFileType());
        // 库已删（反查不到）回落占位，不让列表行空白
        assertEquals("知识库 99", result.getRecords().get(1).getKnowledgeBaseName());
        assertEquals("FILE_TOO_LARGE", result.getRecords().get(1).getFailReason());
        assertEquals("文件超过大小上限", result.getRecords().get(1).getFailReasonLabel());
        // 校验失败的行没有来源文件，类型留空
        assertNull(result.getRecords().get(2).getFileType());
    }

    /**
     * 来源文件的 MIME 不在格式目录时类型留空。
     *
     * <p>守护 {@code resolveFileType} 的未知 MIME 兜底：猜扩展名即失败。
     */
    @Test
    void recentSubmitsShouldLeaveFileTypeBlankForUnknownMime() {
        WbSubmitLog row = submitRow(11L, "unknown.bin", 1L, "file-9", "SUCCESS", null);
        Page<WbSubmitLog> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(row));
        when(submitLogDbService.pageRecentByUser(1L, 10L, 9L)).thenReturn(page);
        WbSourceFile source = new WbSourceFile();
        source.setFileId("file-9");
        source.setMimeType("application/x-unknown");
        when(sourceFileDbService.listByFileIds(any())).thenReturn(List.of(source));

        IPage<com.workbuddy.common.dto.response.home.HomeRecentSubmitVO> result =
                service.recentSubmits(query(1L, 10L));

        assertNull(result.getRecords().get(0).getFileType());
    }

    /**
     * 确认两个批量反查都没被调用。
     */
    private void verifyNoSourceLookups() {
        org.mockito.Mockito.verifyNoInteractions(knowledgeBaseDbService, sourceFileDbService);
    }

    /**
     * 构造登录用户。
     *
     * @param id   用户 ID
     * @return 登录用户
     */
    private KnowledgeUser user(Long id) {
        return user(id, com.workbuddy.common.enums.user.UserRole.USER);
    }

    /**
     * 构造指定角色的登录用户。
     *
     * @param id   用户 ID
     * @param role 角色
     * @return 登录用户
     */
    private KnowledgeUser user(Long id, com.workbuddy.common.enums.user.UserRole role) {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    /**
     * 构造分页查询条件。
     *
     * @param current 当前页
     * @param size    每页条数
     * @return 查询条件
     */
    private HomeRecentSubmitQueryDTO query(Long current, Long size) {
        HomeRecentSubmitQueryDTO query = new HomeRecentSubmitQueryDTO();
        query.setCurrent(current);
        query.setSize(size);
        return query;
    }

    /**
     * 构造提交日志行。
     *
     * @param id              记录 ID
     * @param fileName        文件名
     * @param knowledgeBaseId 知识库 ID（可空）
     * @param fileId          来源文件 ID（可空）
     * @param status          状态
     * @param failReason      失败原因码（可空）
     * @return 提交日志行
     */
    private WbSubmitLog submitRow(Long id, String fileName, Long knowledgeBaseId, String fileId,
                                  String status, String failReason) {
        WbSubmitLog row = new WbSubmitLog();
        row.setId(id);
        row.setFileName(fileName);
        row.setKnowledgeBaseId(knowledgeBaseId);
        row.setFileId(fileId);
        row.setStatus(status);
        row.setFailReason(failReason);
        row.setCreateTime(java.time.LocalDateTime.of(2026, 10, 9, 10, 0));
        return row;
    }
}
