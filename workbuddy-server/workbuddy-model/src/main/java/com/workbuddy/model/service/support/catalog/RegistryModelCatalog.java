package com.workbuddy.model.service.support.catalog;

import com.workbuddy.common.convert.model.ModelConverter;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.model.ModelCatalogPort;
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

    private final ModelConverter modelConverter;

    /**
     * 按能力取候选。
     *
     * @param capability 能力
     * @return 候选（注册表顺序）
     */
    @Override
    public List<ModelDescriptor> listByCapability(ModelCapability capability) {
        return modelRegistry.byCapability(capability).stream()
                .map(modelConverter::toDescriptor)
                .toList();
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
        return modelRegistry.find(vendorCode, modelKey).map(modelConverter::toDescriptor);
    }
}
