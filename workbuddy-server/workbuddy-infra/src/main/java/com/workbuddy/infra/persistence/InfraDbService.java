package com.workbuddy.infra.persistence;

import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 通用数据访问服务接口基类：继承 MyBatis-Plus IService，提供对象级持久化能力。
 *
 * @param <T> 实体类型
 */
public interface InfraDbService<T> extends IService<T> {
}
