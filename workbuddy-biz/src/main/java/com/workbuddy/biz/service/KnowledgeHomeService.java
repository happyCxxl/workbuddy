package com.workbuddy.biz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.dto.request.home.HomeRecentSubmitQueryDTO;
import com.workbuddy.common.dto.response.home.HomeRecentSubmitVO;
import com.workbuddy.common.dto.response.home.HomeSummaryVO;

/**
 * 首页服务：资产速览 + 最近提交。
 *
 * <p>首页只承担两件事：**有多少资产** 与 **我最近提交了什么**。
 * 提交记录只含**当前登录用户**的（可见范围在服务端定）；
 * 审计动作（`wb_audit_log`）不在首页展示，数据照旧由审计链路写入。
 */
public interface KnowledgeHomeService {

    /**
     * 资产速览：知识库（总数 + 启用数）/ 可用策略版本（四类细分）/ 已建档文档数。
     */
    HomeSummaryVO summary();

    /**
     * 最近提交：**当前登录用户**的文件提交记录（新→旧），附所属知识库名与文件类型。
     *
     * <p>`status` 是**提交校验结果**（PASS/FAIL），不是处理链进度。
     *
     * @param query 分页条件
     */
    IPage<HomeRecentSubmitVO> recentSubmits(HomeRecentSubmitQueryDTO query);
}
