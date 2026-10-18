package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.WbEmbeddingRecordMapper;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量记录数据访问服务实现。
 */
@Service
public class WbEmbeddingRecordDbServiceImpl extends InfraDbServiceImpl<WbEmbeddingRecordMapper, WbEmbeddingRecord>
        implements WbEmbeddingRecordDbService {

    @Override
    public List<WbEmbeddingRecord> listByEmbeddingSetId(Long embeddingSetId) {
        LambdaQueryWrapper<WbEmbeddingRecord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbEmbeddingRecord::getEmbeddingSetId, embeddingSetId)
                .orderByAsc(WbEmbeddingRecord::getId);
        return list(queryWrapper);
    }
}
