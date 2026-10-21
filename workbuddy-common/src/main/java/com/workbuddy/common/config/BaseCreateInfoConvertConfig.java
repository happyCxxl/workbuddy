package com.workbuddy.common.config;

import org.mapstruct.MapperConfig;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 继承 {@code BaseCreateInfo}（只记创建人与创建时间）的实体转换基线。
 *
 * <p>原型方法 `serverFields` 声明主键与创建列不由请求体决定；清单必须与目标实体公共字段完全一致。
 */
@MapperConfig(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BaseCreateInfoConvertConfig {

    /**
     * 公共字段忽略清单（只记创建信息）。
     *
     * @param source 任意来源
     * @return 任意目标
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    Object serverFields(Object source);
}
