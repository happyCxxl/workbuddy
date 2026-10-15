package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.mapper.KnowledgeBaseMapper;
import com.workbuddy.biz.service.db.KnowledgeBaseDbService;
import com.workbuddy.common.domain.entity.KnowledgeBase;
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
 *
 * @author cxxl
 */
@Service
public class KnowledgeBaseDbServiceImpl extends InfraDbServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseDbService {

    @Override
    public IPage<KnowledgeBase> pageByCondition(long current, long size, String name, Integer status,
                                                KnowledgeBaseSort sort, Long ownerId) {
        LambdaQueryWrapper<KnowledgeBase> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StrUtil.isNotBlank(name), KnowledgeBase::getName, name)
                .eq(NullUtil.isNotNull(status), KnowledgeBase::getStatus, status)
                // 归属过滤：ownerId 为 null 时不加条件（管理员视角）
                .eq(NullUtil.isNotNull(ownerId), KnowledgeBase::getUserId, ownerId);
        switch (NullUtil.isNull(sort) ? KnowledgeBaseSort.DEFAULT : sort) {
            case UPDATED -> queryWrapper.orderByDesc(KnowledgeBase::getUpdateTime)
                    .orderByDesc(KnowledgeBase::getId);
            case NAME -> queryWrapper.orderByAsc(KnowledgeBase::getName)
                    .orderByAsc(KnowledgeBase::getId);
            default -> queryWrapper.orderByDesc(KnowledgeBase::getId);
        }
        return page(new Page<>(current, size), queryWrapper);
    }

    @Override
    public long countByStatus(Integer status, Long ownerId) {
        LambdaQueryWrapper<KnowledgeBase> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(NullUtil.isNotNull(status), KnowledgeBase::getStatus, status)
                .eq(NullUtil.isNotNull(ownerId), KnowledgeBase::getUserId, ownerId);
        return count(queryWrapper);
    }

    @Override
    public List<Long> listIdsByOwner(Long ownerId) {
        LambdaQueryWrapper<KnowledgeBase> queryWrapper = new LambdaQueryWrapper<>();
        // 只取主键列：返回的就是 ID 集合，拉回整行没有意义
        queryWrapper.select(KnowledgeBase::getId)
                .eq(NullUtil.isNotNull(ownerId), KnowledgeBase::getUserId, ownerId);
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
    @Override
    public KnowledgeBase getActiveById(Long id) {
        KnowledgeBase kb = getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(kb), ErrorCode.KB_NOT_FOUND);
        return kb;
    }
}
