package com.workbuddy.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workbuddy.common.domain.entity.KbStorageSource;
import org.apache.ibatis.annotations.Mapper;

/**
 * 存储数据源 Mapper（表：kb_storage_source）。
 *
 * @author cxxl
 */
@Mapper
public interface KbStorageSourceMapper extends BaseMapper<KbStorageSource> {
}
