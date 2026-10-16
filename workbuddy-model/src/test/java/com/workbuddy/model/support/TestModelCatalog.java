package com.workbuddy.model.support;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.embed.EmbeddingModel;
import com.workbuddy.model.catalog.ModelCatalogPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试用模型目录：显式给定「模型名 → 厂商码」的映射，其余查询按 {@link EmbeddingModel} 枚举作答。
 * 不连库，也不依赖配置里的厂商，供目录解析类用例构造确定性的解析结果。
 *
 * @author cxxl
 */
public class TestModelCatalog implements ModelCatalogPort {

    private final Map<String, String> vendors = new LinkedHashMap<>();

    /**
     * 登记一个模型名与厂商码的对应关系。
     *
     * @param modelKey   模型名
     * @param vendorCode 厂商码
     * @return 本对象（链式登记）
     */
    public TestModelCatalog withModel(String modelKey, String vendorCode) {
        vendors.put(modelKey, vendorCode);
        return this;
    }

    @Override
    public List<ModelDescriptor> listByCapability(ModelCapability capability) {
        List<ModelDescriptor> candidates = new ArrayList<>();
        if (capability != ModelCapability.EMBEDDING) {
            return candidates;
        }
        for (Map.Entry<String, String> entry : vendors.entrySet()) {
            ModelDescriptor descriptor = toDescriptor(entry.getKey(), entry.getValue());
            if (descriptor != null && descriptor.enabled()) {
                candidates.add(descriptor);
            }
        }
        return candidates;
    }

    @Override
    public ModelDescriptor findByVendorAndModelKey(String vendorCode, String modelKey) {
        ModelDescriptor descriptor = findByModelKey(modelKey);
        if (descriptor != null && vendorCode != null && vendorCode.equals(descriptor.getVendorCode())) {
            return descriptor;
        }
        return null;
    }

    @Override
    public ModelDescriptor findByModelKey(String modelKey) {
        if (modelKey == null) {
            return null;
        }
        String vendorCode = vendors.get(modelKey);
        if (vendorCode == null && EmbeddingModel.of(modelKey) == null) {
            return null;
        }
        return toDescriptor(modelKey, vendorCode);
    }

    @Override
    public ModelDescriptor findUniqueEnabledByModelKey(String modelKey) {
        ModelDescriptor descriptor = findByModelKey(modelKey);
        return descriptor != null && descriptor.enabled() ? descriptor : null;
    }

    /** 模型名与厂商码 → 目录视图：向量规格与启停状态取自枚举，枚举里没有该模型名即不带向量规格 */
    private ModelDescriptor toDescriptor(String modelKey, String vendorCode) {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setVendorCode(vendorCode);
        descriptor.setModelKey(modelKey);
        descriptor.setCapabilities(Set.of(ModelCapability.EMBEDDING));
        EmbeddingModel model = EmbeddingModel.of(modelKey);
        descriptor.setStatus(model != null && model.enabled() ? "ENABLED" : "DISABLED");
        if (model != null) {
            descriptor.setContextWindow(model.contextWindowTokens());
            ModelDescriptor.EmbedSpecs specs = new ModelDescriptor.EmbedSpecs();
            specs.setDimension(model.dimension());
            specs.setMetric(model.metric());
            specs.setNormalized(model.normalized());
            specs.setContextWindowTokens(model.contextWindowTokens());
            specs.setBatchLimit(model.batchLimit());
            descriptor.setEmbedSpecs(specs);
        }
        return descriptor;
    }
}
