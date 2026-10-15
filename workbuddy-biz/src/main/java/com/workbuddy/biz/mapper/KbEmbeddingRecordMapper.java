package com.workbuddy.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workbuddy.common.domain.entity.KbEmbeddingRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 向量记录 Mapper。
 *
 * @author cxxl
 */
@Mapper
public interface KbEmbeddingRecordMapper extends BaseMapper<KbEmbeddingRecord> {
}
