package com.workbuddy.common.config;

import org.mapstruct.MapperConfig;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 继承 {@link com.workbuddy.common.domain.base.BaseInfo} 的实体转换基线。
 *
 * <p>原型方法 `serverFields` 声明公共字段不由请求体决定：主键、逻辑删除标记与审计列
 * 由统一填充器/主键策略写入。实体转换方法用 `@InheritConfiguration(name = "serverFields")`
 * 继承这份清单，避免逐方法重复 `@Mapping(ignore = true)`。
 *
 * <p>该清单必须与目标实体的公共字段完全一致：多写一个目标不存在的属性会编译失败。
 */
@MapperConfig(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BaseInfoConvertConfig {

    /**
     * 公共字段忽略清单（完整审计列）。
     *
     * @param source 任意来源
     * @return 任意目标
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "delFlag", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateBy", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    Object serverFields(Object source);
}
