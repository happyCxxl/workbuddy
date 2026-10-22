package com.workbuddy.model;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.ModelCapability;

import java.util.List;
import java.util.Optional;

/**
 * 模型目录端口：按能力取候选、按厂商与模型名定位；实现读注册表快照，不查库。
 */
public interface ModelCatalogPort {

    /**
     * 按能力取候选（只含启用行）。
     *
     * @param capability 能力
     * @return 候选（注册表顺序）
     */
    List<ModelDescriptor> listByCapability(ModelCapability capability);

    /**
     * 按厂商码与模型名定位一行。
     *
     * @param vendorCode 厂商码，可空
     * @param modelKey   厂商侧模型名，可空
     * @return 命中行；参数为空或未命中时为空
     */
    Optional<ModelDescriptor> find(String vendorCode, String modelKey);
}
