package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.KbEmbeddingRecordMapper;
import com.workbuddy.biz.service.db.KbEmbeddingRecordDbService;
import com.workbuddy.common.domain.entity.KbEmbeddingRecord;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量记录数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class KbEmbeddingRecordDbServiceImpl extends InfraDbServiceImpl<KbEmbeddingRecordMapper, KbEmbeddingRecord>
        implements KbEmbeddingRecordDbService {

    @Override
    public List<KbEmbeddingRecord> listByEmbeddingSetId(Long embeddingSetId) {
        LambdaQueryWrapper<KbEmbeddingRecord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbEmbeddingRecord::getEmbeddingSetId, embeddingSetId)
                .orderByAsc(KbEmbeddingRecord::getId);
        return list(queryWrapper);
    }
}
