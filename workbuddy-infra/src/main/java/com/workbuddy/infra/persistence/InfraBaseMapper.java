package com.workbuddy.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 通用 Mapper 基类：继承 MyBatis-Plus BaseMapper。
 *
 * @param <T> 实体类型
 * @author cxxl
 */
public interface InfraBaseMapper<T> extends BaseMapper<T> {
}
