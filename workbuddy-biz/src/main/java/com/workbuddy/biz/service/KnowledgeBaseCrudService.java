package com.workbuddy.biz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseQueryDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDTO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;

/**
 * 知识库管理用例：创建/更新/详情/分页/启停/逻辑删除与总览统计（含同事务审计）。
 *
 * <p>可见范围由服务端按当前用户归属过滤：普通用户只能读写自己创建的库，管理员不限归属；
 * 按 ID 的读写不可访问时按「不存在」处理（40401）。
 */
public interface KnowledgeBaseCrudService {

    /**
     * 创建知识库（默认启用状态），并写 CREATE 审计。
     *
     * @param dto 新建请求
     * @return 新知识库 ID
     */
    Long create(KnowledgeBaseCreateDTO dto);

    /**
     * 更新名称/描述/策略绑定开关，并写 UPDATE 审计。
     *
     * @param dto 修改请求
     * @return 更新成功为 true
     */
    @SuppressWarnings("SameReturnValue")
    boolean update(KnowledgeBaseUpdateDTO dto);

    /**
     * 详情（已逻辑删除不可见），回填文档数与三类策略绑定摘要。
     *
     * @param id 知识库 ID
     * @return 知识库详情
     */
    KnowledgeBaseVO detail(Long id);

    /**
     * 分页列表（仅当前用户可见的库；名称模糊，不含已删），回填文档数、已发布索引版本与策略绑定摘要。
     *
     * @param query 查询条件（名称/状态/排序都可留空，页码与页长口径见父类；排序未知取值按默认口径）
     * @return 知识库分页
     */
    IPage<KnowledgeBaseVO> page(KnowledgeBaseQueryDTO query);

    /**
     * 统计概览（仅未删除数据）：知识库总数、启用数、文档数。
     *
     * <p>三个数与 {@link #page} 同一可见范围：文档数先取可见库 ID 再按集合计数
     * （`wb_file_result` 没有归属列）。
     *
     * @return 统计视图
     */
    KnowledgeBaseStatsVO stats();

    /**
     * 停用（仅启用状态可停用），并写 DISABLE 审计。
     *
     * @param id 知识库 ID
     * @return 变更成功为 true
     */
    @SuppressWarnings("SameReturnValue")
    boolean disable(Long id);

    /**
     * 启用（仅停用状态可启用），并写 ENABLE 审计。
     *
     * @param id 知识库 ID
     * @return 变更成功为 true
     */
    @SuppressWarnings("SameReturnValue")
    boolean enable(Long id);

    /**
     * 逻辑删除（del_flag='1'，无恢复接口），并写 DELETE 审计。
     *
     * @param id 知识库 ID
     * @return 删除成功为 true
     */
    @SuppressWarnings("SameReturnValue")
    boolean delete(Long id);
}
