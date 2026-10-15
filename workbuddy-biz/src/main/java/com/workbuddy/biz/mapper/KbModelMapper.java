package com.workbuddy.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workbuddy.common.domain.entity.KbModel;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型目录 Mapper（表：kb_model）。
 *
 * @author cxxl
 */
@Mapper
public interface KbModelMapper extends BaseMapper<KbModel> {
}
