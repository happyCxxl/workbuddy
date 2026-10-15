package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.collection.CollUtil;
import com.workbuddy.biz.mapper.KbFileResultMapper;
import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.common.domain.entity.KbFileResult;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件结果数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class KbFileResultDbServiceImpl extends InfraDbServiceImpl<KbFileResultMapper, KbFileResult>
        implements KbFileResultDbService {

    @Override
    public IPage<KbFileResult> pageByKb(long current, long size, Long knowledgeBaseId) {
        LambdaQueryWrapper<KbFileResult> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbFileResult::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(KbFileResult::getId);
        return page(new Page<>(current, size), queryWrapper);
    }

    @Override
    public List<KbFileResult> listByKb(Long knowledgeBaseId) {
        LambdaQueryWrapper<KbFileResult> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbFileResult::getKnowledgeBaseId, knowledgeBaseId)
                .orderByAsc(KbFileResult::getId);
        return list(queryWrapper);
    }

    @Override
    public long countByKb(Long knowledgeBaseId) {
        LambdaQueryWrapper<KbFileResult> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbFileResult::getKnowledgeBaseId, knowledgeBaseId);
        return count(queryWrapper);
    }

    @Override
    public Map<Long, Long> countGroupByKb(List<Long> knowledgeBaseIds) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return Map.of();
        }
        QueryWrapper<KbFileResult> queryWrapper = new QueryWrapper<>();
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

    @Override
    public long countByKbIds(List<Long> knowledgeBaseIds) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return 0L;
        }
        LambdaQueryWrapper<KbFileResult> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(KbFileResult::getKnowledgeBaseId, knowledgeBaseIds);
        return count(queryWrapper);
    }
}
