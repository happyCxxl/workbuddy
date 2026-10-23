package com.workbuddy.common.convert.knowledge;

import com.workbuddy.common.config.BaseInfoConvertConfig;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import org.mapstruct.InheritConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 知识库转换：创建请求 → 实体。只搬字段，不查库、不算统计。
 */
@Mapper(config = BaseInfoConvertConfig.class)
public interface KnowledgeBaseConverter {

    /**
     * 创建请求 → 知识库实体（仅字段搬运）。
     *
     * @param dto 创建请求
     * @return 知识库实体（服务端字段未填）
     */
    @InheritConfiguration(name = "serverFields")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "bindingProfileVersionId", ignore = true)
    @Mapping(target = "indexConfig", ignore = true)
    @Mapping(target = "defaultRuleId", ignore = true)
    @Mapping(target = "publishedIndexSetId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    WbKnowledgeBase toEntity(KnowledgeBaseCreateDTO dto);
}
