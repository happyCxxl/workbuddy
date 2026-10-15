package com.workbuddy.biz.service.db;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.infra.persistence.InfraDbService;

/**
 * 提交日志数据访问服务（wb_submit_log）：append-only，只新增与查询。
 *
 * @author cxxl
 */
public interface WbSubmitLogDbService extends InfraDbService<WbSubmitLog> {

    /**
     * 按幂等键查提交日志。
     *
     * @param requestId 幂等键（uk_request_id 唯一）
     * @return 提交日志实体；不存在返回 null
     */
    WbSubmitLog getByRequestId(String requestId);

    /**
     * 按知识库分页查提交记录（id 倒序，新→旧）。
     *
     * @param current        当前页，从 1 开始
     * @param size           每页条数
     * @param knowledgeBaseId 知识库 ID
     * @param status         提交结果筛选（PASS/FAIL；为空不过滤）
     * @param fileName       文件名模糊筛选（为空不过滤）
     * @return 提交日志分页
     */
    IPage<WbSubmitLog> pageByKb(long current, long size, Long knowledgeBaseId, String status, String fileName);

    /**
     * 按提交人分页查最近提交（id 倒序，新→旧）—— 首页「最近提交」用。
     *
     * <p>不复用 {@link #pageByKb}：那条按 `knowledge_base_id = ?` 精确匹配，
     * 传 null 会生成 `= NULL` 恒不成立（不是"不过滤"），拿不到跨库结果。
     * 同理 `user_id` **必须给值** —— 首页只展示本人提交，可见范围在服务端定。
     *
     * @param current 当前页，从 1 开始
     * @param size    每页条数
     * @param userId  提交人用户 ID（当前登录用户）
     * @return 提交日志分页
     */
    IPage<WbSubmitLog> pageRecentByUser(long current, long size, Long userId);
}
