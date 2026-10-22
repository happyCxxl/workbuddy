package com.workbuddy.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workbuddy.common.domain.entity.WbModelCall;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型调用明细映射（表：wb_model_call）：只插入，不更新。
 */
@Mapper
public interface WbModelCallMapper extends BaseMapper<WbModelCall> {
}
