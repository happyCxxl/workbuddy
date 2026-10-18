package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.WbRetrievalRunMapper;
import com.workbuddy.biz.service.db.WbRetrievalRunDbService;
import com.workbuddy.common.domain.entity.WbRetrievalRun;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 检索运行记录数据访问服务实现。
 */
@Service
public class WbRetrievalRunDbServiceImpl
        extends InfraDbServiceImpl<WbRetrievalRunMapper, WbRetrievalRun>
        implements WbRetrievalRunDbService {

    @Override
    public List<WbRetrievalRun> listByKb(Long knowledgeBaseId, int limit) {
        LambdaQueryWrapper<WbRetrievalRun> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbRetrievalRun::getKbId, knowledgeBaseId)
                .orderByDesc(WbRetrievalRun::getId);
        if (limit > 0) {
            queryWrapper.last("LIMIT " + limit);
        }
        return list(queryWrapper);
    }

    @Override
    public List<WbRetrievalRun> listByIdsOrdered(Collection<Long> ids) {
        if (NullUtil.isNull(ids) || ids.isEmpty()) {
            return List.of();
        }
        List<WbRetrievalRun> rows = listByIds(ids);
        return ids.stream()
                .map(id -> rows.stream().filter(r -> id.equals(r.getId())).findFirst().orElse(null))
                .filter(NullUtil::isNotNull)
                .toList();
    }
}
