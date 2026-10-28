package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.mapper.WbKnowledgeBaseMapper;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseSort;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库数据访问服务实现。
 */
@Service
public class WbKnowledgeBaseDbServiceImpl extends InfraDbServiceImpl<WbKnowledgeBaseMapper, WbKnowledgeBase>
        implements WbKnowledgeBaseDbService {

    /**
     * 按条件分页查知识库，命中条件为空则不加该过滤条件。
     *
     * @param current 页码
     * @param size    每页条数
     * @param name    名称模糊匹配（可空）
     * @param status  状态码值（可空）
     * @param sort    排序方式（可空取默认）
     * @param ownerId 归属用户 ID（可空表示不加归属过滤）
     * @return 知识库分页
     */
    @Override
    public IPage<WbKnowledgeBase> pageByCondition(long current, long size, String name, Integer status,
                                                KnowledgeBaseSort sort, Long ownerId) {
        LambdaQueryWrapper<WbKnowledgeBase> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StrUtil.isNotBlank(name), WbKnowledgeBase::getName, name)
                .eq(NullUtil.isNotNull(status), WbKnowledgeBase::getStatus, status)
                // 归属过滤：ownerId 为 null 时不加条件（管理员视角）
                .eq(NullUtil.isNotNull(ownerId), WbKnowledgeBase::getUserId, ownerId);
        switch (NullUtil.isNull(sort) ? KnowledgeBaseSort.DEFAULT : sort) {
            case UPDATED -> queryWrapper.orderByDesc(WbKnowledgeBase::getUpdateTime)
                    .orderByDesc(WbKnowledgeBase::getId);
            case NAME -> queryWrapper.orderByAsc(WbKnowledgeBase::getName)
                    .orderByAsc(WbKnowledgeBase::getId);
            default -> queryWrapper.orderByDesc(WbKnowledgeBase::getId);
        }
        return page(new Page<>(current, size), queryWrapper);
    }

    /**
     * 按状态与归属统计知识库数。
     *
     * @param status  状态码值（可空）
     * @param ownerId 归属用户 ID（可空表示不限）
     * @return 知识库数
     */
    @Override
    public long countByStatus(Integer status, Long ownerId) {
        LambdaQueryWrapper<WbKnowledgeBase> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(NullUtil.isNotNull(status), WbKnowledgeBase::getStatus, status)
                .eq(NullUtil.isNotNull(ownerId), WbKnowledgeBase::getUserId, ownerId);
        return count(queryWrapper);
    }

    /**
     * 取归属某用户的全部知识库 ID。
     *
     * @param ownerId 归属用户 ID
     * @return 知识库 ID 列表
     */
    @Override
    public List<Long> listIdsByOwner(Long ownerId) {
        LambdaQueryWrapper<WbKnowledgeBase> queryWrapper = new LambdaQueryWrapper<>();
        // 只取主键列：返回的就是 ID 集合，拉回整行没有意义
        queryWrapper.select(WbKnowledgeBase::getId)
                .eq(NullUtil.isNotNull(ownerId), WbKnowledgeBase::getUserId, ownerId);
        List<Long> ids = new ArrayList<>();
        for (Object value : baseMapper.selectObjs(queryWrapper)) {
            if (NullUtil.isNotNull(value)) {
                ids.add(((Number) value).longValue());
            }
        }
        return ids;
    }

    /**
     * 获取存在且未删除的知识库：getById 后判空，不存在/已删除抛 KB_NOT_FOUND（40401）。
     */
    /**
     * 取处于启用状态的知识库。
     *
     * @param id 知识库 ID
     * @return 知识库行；不存在或未启用时为 null
     */
    @Override
    public WbKnowledgeBase getActiveById(Long id) {
        WbKnowledgeBase kb = getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(kb), ErrorCode.KB_NOT_FOUND);
        return kb;
    }
}
