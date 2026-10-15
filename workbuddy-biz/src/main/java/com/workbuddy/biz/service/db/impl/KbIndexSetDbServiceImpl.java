package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.workbuddy.biz.mapper.KbIndexSetMapper;
import com.workbuddy.biz.service.db.KbIndexSetDbService;
import com.workbuddy.common.domain.entity.KbIndexSet;
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
 *
 * @author cxxl
 */
@Slf4j
@Service
public class KbIndexSetDbServiceImpl extends ServiceImpl<KbIndexSetMapper, KbIndexSet>
        implements KbIndexSetDbService {

    @Override
    public KbIndexSet getByKb(Long knowledgeBaseId) {
        LambdaQueryWrapper<KbIndexSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbIndexSet::getKnowledgeBaseId, knowledgeBaseId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public Map<Long, KbIndexSet> listByKbIds(List<Long> knowledgeBaseIds) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return Map.of();
        }
        LambdaQueryWrapper<KbIndexSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(KbIndexSet::getKnowledgeBaseId, knowledgeBaseIds);
        return list(queryWrapper).stream()
                .collect(Collectors.toMap(KbIndexSet::getKnowledgeBaseId, Function.identity(), (a, b) -> a));
    }

    @Override
    public KbIndexSet getOrCreateByKb(Long knowledgeBaseId) {
        KbIndexSet existing = getByKb(knowledgeBaseId);
        if (NullUtil.isNotNull(existing)) {
            return existing;
        }
        KbIndexSet created = new KbIndexSet();
        created.setKnowledgeBaseId(knowledgeBaseId);
        try {
            save(created);
            return created;
        } catch (DuplicateKeyException e) {
            // uk_kb 并发兜底：冲突后返回既有行
            log.info("===> KbIndexSetDbServiceImpl 集合行并发创建冲突, 返回既有行, kbId={}", knowledgeBaseId);
            return getByKb(knowledgeBaseId);
        }
    }

    @Override
    public void updatePublishedVersion(Long setId, Long versionId) {
        LambdaUpdateWrapper<KbIndexSet> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(KbIndexSet::getId, setId)
                .set(KbIndexSet::getCurrentPublishedVersionId, versionId);
        update(updateWrapper);
    }
}
