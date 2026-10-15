package com.workbuddy.biz.service.db;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.domain.entity.KnowledgeBase;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseSort;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 知识库数据访问服务（表：kb_knowledge_base）。
 *
 * @author cxxl
 */
public interface KnowledgeBaseDbService extends InfraDbService<KnowledgeBase> {

    /**
     * 分页查询（名称模糊 + 状态精确过滤 + 归属过滤；@TableLogic 自动过滤已逻辑删除）。
     *
     * @param current 当前页，从 1 开始
     * @param size    每页条数
     * @param name    名称关键字（模糊匹配；为空时不过滤）
     * @param status  状态：1 启用 / 0 停用；null 不过滤
     * @param sort    排序口径；null 按默认口径
     * @param ownerId 归属过滤：传用户 ID 只查该用户创建的库；**null 表示不过滤（管理员视角）**。
     *                取值口径见 {@code KnowledgeBaseRules.visibleOwnerId} —— 它就是
     *                "看不到自己的库"与"看到所有人的库"的分界
     * @return 知识库分页
     */
    IPage<KnowledgeBase> pageByCondition(long current, long size, String name, Integer status,
                                        KnowledgeBaseSort sort, Long ownerId);

    /**
     * 按状态统计知识库数（@TableLogic 自动过滤已逻辑删除）。
     *
     * @param status  状态：1 启用 / 0 停用；null 统计全部
     * @param ownerId 归属过滤：传用户 ID 只数该用户创建的库；null 不过滤（管理员视角），
     *                取值口径同 {@link #pageByCondition}
     * @return 知识库数
     */
    long countByStatus(Integer status, Long ownerId);

    /**
     * 取某归属下全部知识库 ID（@TableLogic 自动过滤已逻辑删除）。
     *
     * <p>用途：把"按归属的文档数"换算成"按知识库集合的文档数"
     * （文档表只有 knowledge_base_id，没有归属列，见
     * {@link KbFileResultDbService#countByKbIds}）。
     *
     * @param ownerId 归属过滤：传用户 ID 只取该用户创建的库；null 取全部（管理员视角）
     * @return 知识库 ID 列表；无数据返回空列表
     */
    List<Long> listIdsByOwner(Long ownerId);

    /**
     * 获取存在且未删除的知识库（对象级获取语义）。
     *
     * @param id 知识库 ID
     * @return 知识库实体
     * @apiNote 不存在或已删除抛 KnowledgeException（KB_NOT_FOUND 40401）
     */
    KnowledgeBase getActiveById(Long id);
}
