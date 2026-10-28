package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.collection.CollUtil;
import com.workbuddy.biz.mapper.WbFileResultMapper;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件结果数据访问服务实现。
 */
@Service
public class WbFileResultDbServiceImpl extends InfraDbServiceImpl<WbFileResultMapper, WbFileResult>
        implements WbFileResultDbService {

    /**
     * 按知识库分页查文件结果。
     *
     * @param current         页码
     * @param size            每页条数
     * @param knowledgeBaseId 知识库 ID
     * @return 文件结果分页
     */
    @Override
    public IPage<WbFileResult> pageByKb(long current, long size, Long knowledgeBaseId) {
        LambdaQueryWrapper<WbFileResult> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbFileResult::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(WbFileResult::getId);
        return page(new Page<>(current, size), queryWrapper);
    }

    /**
     * 取某知识库下的全部文件结果。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 文件结果列表
     */
    @Override
    public List<WbFileResult> listByKb(Long knowledgeBaseId) {
        LambdaQueryWrapper<WbFileResult> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbFileResult::getKnowledgeBaseId, knowledgeBaseId)
                .orderByAsc(WbFileResult::getId);
        return list(queryWrapper);
    }

    /**
     * 统计某知识库下的文件数。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 文件数
     */
    @Override
    public long countByKb(Long knowledgeBaseId) {
        LambdaQueryWrapper<WbFileResult> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbFileResult::getKnowledgeBaseId, knowledgeBaseId);
        return count(queryWrapper);
    }

    /**
     * 按知识库分组统计文件数。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @return 知识库 ID 到文件数的映射
     */
    @Override
    public Map<Long, Long> countGroupByKb(List<Long> knowledgeBaseIds) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return Map.of();
        }
        QueryWrapper<WbFileResult> queryWrapper = new QueryWrapper<>();
        // COUNT(*) 显式起别名，避免依赖驱动返回的列名
        queryWrapper.select("knowledge_base_id AS kbId", "COUNT(*) AS docCount")
                .in("knowledge_base_id", knowledgeBaseIds)
                .groupBy("knowledge_base_id");
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : baseMapper.selectMaps(queryWrapper)) {
            Object kbId = row.get("kbId");
            Object docCount = row.get("docCount");
            if (NullUtil.isNotNull(kbId) && NullUtil.isNotNull(docCount)) {
                result.put(((Number) kbId).longValue(), ((Number) docCount).longValue());
            }
        }
        return result;
    }

    /**
     * 统计一组知识库的文件总数。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @return 文件数
     */
    @Override
    public long countByKbIds(List<Long> knowledgeBaseIds) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return 0L;
        }
        LambdaQueryWrapper<WbFileResult> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WbFileResult::getKnowledgeBaseId, knowledgeBaseIds);
        return count(queryWrapper);
    }
}
