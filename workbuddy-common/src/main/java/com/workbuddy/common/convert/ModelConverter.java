package com.workbuddy.common.convert;

import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.domain.model.RegisteredModel;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.dto.request.model.ModelSaveDTO;
import com.workbuddy.common.enums.embed.EmbeddingModel;
import com.workbuddy.common.utils.NullUtil;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * 模型转换：库表行 → 注册表行 / 目录视图、保存请求 → 模型实体（编译期生成实现，运行期无反射）。
 *
 * <p>字段同名直接搬运；能力 CSV 与向量规格是派生字段，放在表达式 / 后置映射里。
 */
@Mapper(componentModel = "spring")
public interface ModelConverter {

    /**
     * 库表行 → 注册表行：能力 CSV 解析成集合，其余字段同名搬运。
     *
     * @param row 库表行
     * @return 注册表行
     */
    @Mapping(target = "capabilities",
            expression = "java(com.workbuddy.common.enums.model.ModelCapability.parse(row.getCapabilities()))")
    RegisteredModel toRegistered(WbModel row);

    /**
     * 注册表行 → 目录视图。
     *
     * @param model 注册表行
     * @return 目录视图
     */
    @Mapping(target = "embedSpecs", ignore = true)
    ModelDescriptor toDescriptor(RegisteredModel model);

    /**
     * 向量模型叠加静态规格；非向量模型或未启用时留空。
     *
     * @param model      注册表行
     * @param descriptor 已映射的目录视图
     */
    @AfterMapping
    default void fillEmbedSpecs(RegisteredModel model, @MappingTarget ModelDescriptor descriptor) {
        EmbeddingModel embedding = EmbeddingModel.of(model.getModelKey());
        if (NullUtil.isNull(embedding) || !embedding.enabled()) {
            return;
        }
        ModelDescriptor.EmbedSpecs specs = new ModelDescriptor.EmbedSpecs();
        specs.setDimension(embedding.dimension());
        specs.setMetric(embedding.metric());
        specs.setNormalized(embedding.normalized());
        specs.setContextWindowTokens(embedding.contextWindowTokens());
        specs.setBatchLimit(embedding.batchLimit());
        descriptor.setEmbedSpecs(specs);
    }

    /**
     * 模型保存请求 → 模型实体：只搬同名字段；trim、能力解析、默认值与状态由调用方处理。
     *
     * @param dto 保存请求
     * @return 模型实体（未保存）
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "delFlag", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateBy", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "vendorCode", ignore = true)
    @Mapping(target = "modelKey", ignore = true)
    @Mapping(target = "capabilities", ignore = true)
    @Mapping(target = "thinkingEnabled", ignore = true)
    WbModel toModel(ModelSaveDTO dto);

}
