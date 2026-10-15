package com.workbuddy.model.support;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.model.catalog.ModelCatalogPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由用例的假目录（测试用）：用例登记目录项，按能力查询即返回声明了该能力的那些项。
 * 不连库，也不按厂商与状态过滤（闸门由路由实现负责）。
 *
 * @author cxxl
 */
public class TestRouteCatalog implements ModelCatalogPort {

    /** 登记的目录项（键 = 厂商码|模型名） */
    private final Map<String, ModelDescriptor> descriptors = new LinkedHashMap<>();

    /**
     * 登记一个目录项。
     *
     * @param descriptor 目录项
     * @return 本对象（链式登记）
     */
    public TestRouteCatalog register(ModelDescriptor descriptor) {
        descriptors.put(descriptor.getVendorCode() + "|" + descriptor.getModelKey(), descriptor);
        return this;
    }

    @Override
    public List<ModelDescriptor> listByCapability(ModelCapability capability) {
        List<ModelDescriptor> candidates = new ArrayList<>();
        for (ModelDescriptor descriptor : descriptors.values()) {
            if (descriptor.supports(capability)) {
                candidates.add(descriptor);
            }
        }
        return candidates;
    }

    @Override
    public ModelDescriptor findByVendorAndModelKey(String vendorCode, String modelKey) {
        return descriptors.get(vendorCode + "|" + modelKey);
    }

    @Override
    public ModelDescriptor findByModelKey(String modelKey) {
        for (ModelDescriptor descriptor : descriptors.values()) {
            if (descriptor.getModelKey().equals(modelKey)) {
                return descriptor;
            }
        }
        return null;
    }

    @Override
    public ModelDescriptor findUniqueEnabledByModelKey(String modelKey) {
        ModelDescriptor found = findByModelKey(modelKey);
        return found != null && found.enabled() ? found : null;
    }
}
