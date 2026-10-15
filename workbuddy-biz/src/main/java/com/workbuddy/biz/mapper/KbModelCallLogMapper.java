package com.workbuddy.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workbuddy.common.domain.entity.KbModelCallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型调用明细 Mapper（表：kb_model_call_log）。
 *
 * @author cxxl
 */
@Mapper
public interface KbModelCallLogMapper extends BaseMapper<KbModelCallLog> {
}
