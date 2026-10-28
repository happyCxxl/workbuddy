package com.workbuddy.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

/**
 * 通用数据访问服务实现基类：继承 MyBatis-Plus ServiceImpl，提供通用 CRUD 实现。
 *
 * @param <M> Mapper 类型
 * @param <T> 实体类型
 */
public class InfraDbServiceImpl<M extends BaseMapper<T>, T> extends ServiceImpl<M, T> implements InfraDbService<T> {
}
