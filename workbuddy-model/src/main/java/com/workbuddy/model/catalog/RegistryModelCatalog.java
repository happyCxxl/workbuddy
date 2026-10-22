package com.workbuddy.model.catalog;

import com.workbuddy.common.domain.model.RegisteredModel;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.embed.EmbeddingModel;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.registry.ModelRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 目录端口的注册表实现：把内存快照行映射成目录视图，不查库。
 */
@Component
@RequiredArgsConstructor
public class RegistryModelCatalog implements ModelCatalogPort {

    private final ModelRegistry modelRegistry;

    /**
     * 按能力取候选。
     *
     * @param capability 能力
     * @return 候选（注册表顺序）
     */
    @Override
    public List<ModelDescriptor> listByCapability(ModelCapability capability) {
        return modelRegistry.byCapability(capability).stream().map(this::toDescriptor).toList();
    }

    /**
     * 按厂商码与模型名定位。
     *
     * @param vendorCode 厂商码，可空
     * @param modelKey   厂商侧模型名，可空
     * @return 命中行；未命中为空
     */
    @Override
    public Optional<ModelDescriptor> find(String vendorCode, String modelKey) {
        return modelRegistry.find(vendorCode, modelKey).map(this::toDescriptor);
    }

    /** 注册表行 → 目录视图：长度、价格、限额全量搬运；向量模型叠加静态规格 */
    private ModelDescriptor toDescriptor(RegisteredModel model) {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setVendorCode(model.getVendorCode());
        descriptor.setModelKey(model.getModelKey());
        descriptor.setCapabilities(model.getCapabilities());
        descriptor.setContextWindow(model.getContextWindow());
        descriptor.setInputLimit(model.getInputLimit());
        descriptor.setOutputLimit(model.getOutputLimit());
        descriptor.setPriceInPerM(model.getPriceInPerM());
        descriptor.setPriceOutPerM(model.getPriceOutPerM());
        descriptor.setCurrency(model.getCurrency());
        descriptor.setRpmLimit(model.getRpmLimit());
        descriptor.setTpmLimit(model.getTpmLimit());
        descriptor.setStatus(model.getStatus());
        EmbeddingModel embedding = EmbeddingModel.of(model.getModelKey());
        if (NullUtil.isNotNull(embedding) && embedding.enabled()) {
            ModelDescriptor.EmbedSpecs specs = new ModelDescriptor.EmbedSpecs();
            specs.setDimension(embedding.dimension());
            specs.setMetric(embedding.metric());
            specs.setNormalized(embedding.normalized());
            specs.setContextWindowTokens(embedding.contextWindowTokens());
            specs.setBatchLimit(embedding.batchLimit());
            descriptor.setEmbedSpecs(specs);
        }
        return descriptor;
    }
}
