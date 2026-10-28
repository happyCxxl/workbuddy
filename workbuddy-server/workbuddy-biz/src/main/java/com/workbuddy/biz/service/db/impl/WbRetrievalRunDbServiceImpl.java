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

    /**
     * 取某知识库最近的检索运行记录。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param limit           条数上限
     * @return 运行记录列表，按 ID 降序
     */
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

    /**
     * 按 ID 批量取运行记录，并保持传入顺序。
     *
     * @param ids 运行记录 ID 集合
     * @return 运行记录列表；入参为空时返回空列表
     */
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
