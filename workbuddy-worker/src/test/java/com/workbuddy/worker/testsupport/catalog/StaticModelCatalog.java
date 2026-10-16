package com.workbuddy.worker.testsupport.catalog;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.common.enums.embed.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 静态模型目录（测试用）：目录数据 = {@link EmbeddingModel} 枚举（common 单一事实源）。
 * 向量规格（维度 / 度量 / 归一化 / 窗口 / 批上限）与启停状态都取自该枚举，厂商码留空。
 *
 * <p>本类不是应用里的目录实现：应用装配的是 knowledge-biz 的库表实现（wb_model），
 * 本类只供不依赖库的用例按端口契约注入（单元测试与本地假端点用例）。
 *
 * @author cxxl
 */
public class StaticModelCatalog implements ModelCatalogPort {

    @Override
    public List<ModelDescriptor> listByCapability(ModelCapability capability) {
        List<ModelDescriptor> candidates = new ArrayList<>();
        if (capability != ModelCapability.EMBEDDING) {
            return candidates;
        }
        for (EmbeddingModel model : EmbeddingModel.enabledModels()) {
            candidates.add(toDescriptor(model));
        }
        return candidates;
    }

    @Override
    public ModelDescriptor findByVendorAndModelKey(String vendorCode, String modelKey) {
        // 静态目录不表达厂商归属，按厂商码精确查询一律未收录
        return null;
    }

    @Override
    public ModelDescriptor findByModelKey(String modelKey) {
        EmbeddingModel model = EmbeddingModel.of(modelKey);
        return model == null ? null : toDescriptor(model);
    }

    @Override
    public ModelDescriptor findUniqueEnabledByModelKey(String modelKey) {
        EmbeddingModel model = EmbeddingModel.of(modelKey);
        return model == null || !model.enabled() ? null : toDescriptor(model);
    }

    /** 枚举常量 → 目录视图：能力固定为向量编码，厂商码留空（静态目录不表达厂商归属） */
    private ModelDescriptor toDescriptor(EmbeddingModel model) {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setModelKey(model.key());
        descriptor.setCapabilities(Set.of(ModelCapability.EMBEDDING));
        descriptor.setContextWindow(model.contextWindowTokens());
        descriptor.setStatus(model.enabled() ? "ENABLED" : "DISABLED");
        ModelDescriptor.EmbedSpecs specs = new ModelDescriptor.EmbedSpecs();
        specs.setDimension(model.dimension());
        specs.setMetric(model.metric());
        specs.setNormalized(model.normalized());
        specs.setContextWindowTokens(model.contextWindowTokens());
        specs.setBatchLimit(model.batchLimit());
        descriptor.setEmbedSpecs(specs);
        return descriptor;
    }
}
