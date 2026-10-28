package com.workbuddy.common.convert.knowledge;

import com.workbuddy.common.config.BaseInfoConvertConfig;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import org.mapstruct.InheritConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 知识库转换：创建请求 → 实体、实体 → 视图。只搬字段，不查库、不算统计。
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

    /**
     * 知识库实体 → 视图（文档数、策略绑定摘要与发布版本号由调用方回填）。
     *
     * @param kb 知识库实体
     * @return 知识库视图（未回填的字段留空）
     */
    @Mapping(target = "chunkStrategyVersionId", ignore = true)
    @Mapping(target = "chunkStrategyVersion", ignore = true)
    @Mapping(target = "preprocessStrategyVersionId", ignore = true)
    @Mapping(target = "preprocessStrategyVersion", ignore = true)
    @Mapping(target = "embedStrategyVersionId", ignore = true)
    @Mapping(target = "embedStrategyVersion", ignore = true)
    @Mapping(target = "documentCount", ignore = true)
    @Mapping(target = "publishedIndexVersion", ignore = true)
    KnowledgeBaseVO toVO(WbKnowledgeBase kb);
}
