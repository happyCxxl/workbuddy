package com.workbuddy.biz.service.support.access;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.rules.KnowledgeBaseRules;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 文件结果归属守卫：按 fileResultId 直取的接口在服务入口统一过这一关。
 *
 * <p>文件结果自身没有归属列，归属经 `wb_file_result.knowledge_base_id` 反查知识库：
 * 按 ID 取「当前用户可访问的」知识库（存在性 + 归属，与知识库列表那批接口同一口径）。
 *
 * <p>不可访问与不存在在响应上完全一致（{@link ErrorCode#KB_NOT_FOUND}），
 * 不把别人的 ID 变成可探测的信息。
 */
@Component
@RequiredArgsConstructor
public class FileResultAccessGuard {

    private final WbFileResultDbService fileResultDbService;
    private final WbKnowledgeBaseDbService knowledgeBaseDbService;

    /**
     * 取「该文件结果」并按三种语义分别拒绝：**先定登录上下文**（无登录给 UNAUTHORIZED），
     * 再按 ID 取数（记录不存在给 {@link ErrorCode#FILE_RESULT_NOT_FOUND}），最后校验可访问性
     * （不可访问给 {@link ErrorCode#KB_NOT_FOUND}）。
     *
     * <p>登录校验必须排在取数之前：未登录的请求不进入"按 ID 取数"路径，既不做无谓查询，
     * 也不会把"该 ID 有没有记录"透给匿名调用者。
     *
     * @param fileResultId 文件结果 ID
     * @return 文件结果实体
     * @throws WorkbuddyException 无登录上下文（UNAUTHORIZED 40101）、记录不存在（FILE_RESULT_NOT_FOUND 40432）
     *                            或不可访问（KB_NOT_FOUND 40401）
     */
    public WbFileResult requireExisting(Long fileResultId) {
        KnowledgeUser user = SecurityUtil.getUser();
        KnowledgeBaseRules.visibleOwnerId(user);
        WbFileResult fileResult = fileResultDbService.getById(fileResultId);
        ThrowUtil.throwIf(NullUtil.isNull(fileResult), ErrorCode.FILE_RESULT_NOT_FOUND);
        check(fileResult, user);
        return fileResult;
    }


    /**
     * 按指定用户校验「已取到的」文件结果。
     *
     * @param fileResult 已取到的文件结果，可空
     * @param user       登录用户（可空：无登录上下文一律拒绝）
     * @throws WorkbuddyException 无登录上下文（UNAUTHORIZED 40101）或不可访问（KB_NOT_FOUND 40401）
     */
    public void check(WbFileResult fileResult, KnowledgeUser user) {
        KnowledgeBaseRules.visibleOwnerId(user);
        ThrowUtil.throwIf(NullUtil.isNull(fileResult), ErrorCode.KB_NOT_FOUND);
        // 库不存在/已删除（40401）或非本人可访问（40401）
        KnowledgeBaseRules.checkAccessible(
                knowledgeBaseDbService.getActiveById(fileResult.getKnowledgeBaseId()), user);
    }
}
