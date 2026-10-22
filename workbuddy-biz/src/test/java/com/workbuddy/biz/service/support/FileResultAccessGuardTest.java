package com.workbuddy.biz.service.support;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 文件结果归属守卫单测。
 *
 * <p>守护三件事：未登录上下文先拒（40101）、记录不存在按 40432、归属的知识库不可访问统一按 40401；
 * 不可访问与不存在对调用方不可区分，避免把别人 ID 变成可探测的信息。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileResultAccessGuardTest {

    @Mock
    private WbFileResultDbService fileResultDbService;

    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    private MockedStatic<SecurityUtil> security;

    private FileResultAccessGuard guard;

    /**
     * 每个用例重建守卫与登录上下文。
     */
    @BeforeEach
    void setUp() {
        security = Mockito.mockStatic(SecurityUtil.class);
        guard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
    }

    /**
     * 收尾关闭静态桩。
     */
    @AfterEach
    void tearDown() {
        security.close();
    }

    /**
     * 未登录：按 40101 拒绝，且不按 ID 取数。
     *
     * <p>守护 {@code requireExisting} 的登录校验顺序：先取数会把"该 ID 是否存在"透给匿名调用方。
     */
    @Test
    void requireExistingShouldRejectAnonymousBeforeLookup() {
        security.when(SecurityUtil::getUser).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> guard.requireExisting(10L));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), e.getCode());
        org.mockito.Mockito.verify(fileResultDbService, org.mockito.Mockito.never()).getById(10L);
    }

    /**
     * 记录不存在：按 40432 拒绝。
     *
     * <p>守护 {@code requireExisting} 的存在性校验。
     */
    @Test
    void requireExistingShouldRejectMissingRecord() {
        security.when(SecurityUtil::getUser).thenReturn(admin());
        when(fileResultDbService.getById(10L)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> guard.requireExisting(10L));

        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND.getCode(), e.getCode());
    }

    /**
     * 归属的知识库不可访问：按 40401 拒绝。
     *
     * <p>守护 {@code check} 的归属校验：漏校验会让任何人按 ID 读到别人的文件内容。
     */
    @Test
    void checkShouldRejectRecordOfInaccessibleKnowledgeBase() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(null);
        WbFileResult fileResult = new WbFileResult();
        fileResult.setKnowledgeBaseId(2L);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> guard.check(fileResult, normalUser()));

        assertEquals(ErrorCode.KB_NOT_FOUND.getCode(), e.getCode());
    }

    /**
     * 空记录：按 40401 拒绝（与不可访问同一口径）。
     *
     * <p>守护 {@code check} 的空值兜底：漏判会空指针而不是 40401。
     */
    @Test
    void checkShouldRejectNullRecord() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> guard.check(null, normalUser()));

        assertEquals(ErrorCode.KB_NOT_FOUND.getCode(), e.getCode());
    }

    /**
     * 本库可见：校验通过并返回原记录。
     *
     * <p>守护 {@code requireExisting} 的放行路径。
     */
    @Test
    void requireExistingShouldPassForAccessibleKnowledgeBase() {
        security.when(SecurityUtil::getUser).thenReturn(normalUser());
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(1L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setUserId(7L);
        kb.setStatus(1);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(kb);

        assertSame(fileResult, guard.requireExisting(10L));
    }

    /** 普通用户夹具（ID=7） */
    private KnowledgeUser normalUser() {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(7L);
        return user;
    }

    /** 管理员夹具 */
    private KnowledgeUser admin() {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(1L);
        user.setRole(com.workbuddy.common.enums.user.UserRole.ADMIN);
        return user;
    }
}
