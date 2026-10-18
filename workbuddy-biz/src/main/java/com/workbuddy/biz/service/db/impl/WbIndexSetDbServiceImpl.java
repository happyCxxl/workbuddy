package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.workbuddy.biz.mapper.WbIndexSetMapper;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 索引集合数据访问服务实现。
 */
@Slf4j
@Service
public class WbIndexSetDbServiceImpl extends ServiceImpl<WbIndexSetMapper, WbIndexSet>
        implements WbIndexSetDbService {

    @Override
    public WbIndexSet getByKb(Long knowledgeBaseId) {
        LambdaQueryWrapper<WbIndexSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbIndexSet::getKnowledgeBaseId, knowledgeBaseId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public Map<Long, WbIndexSet> listByKbIds(List<Long> knowledgeBaseIds) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return Map.of();
        }
        LambdaQueryWrapper<WbIndexSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WbIndexSet::getKnowledgeBaseId, knowledgeBaseIds);
        return list(queryWrapper).stream()
                .collect(Collectors.toMap(WbIndexSet::getKnowledgeBaseId, Function.identity(), (a, b) -> a));
    }

    @Override
    public WbIndexSet getOrCreateByKb(Long knowledgeBaseId) {
        WbIndexSet existing = getByKb(knowledgeBaseId);
        if (NullUtil.isNotNull(existing)) {
            return existing;
        }
        WbIndexSet created = new WbIndexSet();
        created.setKnowledgeBaseId(knowledgeBaseId);
        try {
            save(created);
            return created;
        } catch (DuplicateKeyException e) {
            // uk_kb 并发兜底：冲突后返回既有行
            log.info("===> WbIndexSetDbServiceImpl 集合行并发创建冲突, 返回既有行, kbId={}", knowledgeBaseId);
            return getByKb(knowledgeBaseId);
        }
    }

    @Override
    public void updatePublishedVersion(Long setId, Long versionId) {
        LambdaUpdateWrapper<WbIndexSet> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(WbIndexSet::getId, setId)
                .set(WbIndexSet::getCurrentPublishedVersionId, versionId);
        update(updateWrapper);
    }
}
