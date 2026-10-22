package com.workbuddy.model.route;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.enums.model.ModelStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.catalog.ModelCatalogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 候选路由：能力 → 启用 → 模型名过滤 → 限额已配 → 交给策略排序。
 *
 * <p>M1 只做硬闸门与直通排序；容量、延迟、冷却在 M2 接入。
 */
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final ModelCatalogPort modelCatalogPort;

    private final RoutingStrategy routingStrategy;

    /**
     * 取本次调用的候选顺序。
     *
     * @param capability     能力，可空（空时无候选）
     * @param modelKeyFilter 精确模型名过滤，可空
     * @return 有序候选；没有候选时为空列表
     */
    public List<ModelDescriptor> candidates(ModelCapability capability, String modelKeyFilter) {
        if (NullUtil.isNull(capability)) {
            return List.of();
        }
        String filter = StrUtil.trimToNull(modelKeyFilter);
        List<ModelDescriptor> matched = modelCatalogPort.listByCapability(capability).stream()
                .filter(model -> ModelStatus.ENABLED.name().equalsIgnoreCase(model.getStatus()))
                .filter(model -> StrUtil.isNotBlank(model.getModelKey()))
                .filter(model -> NullUtil.isNull(filter) || filter.equals(model.getModelKey()))
                .filter(model -> NullUtil.isNotNull(model.getRpmLimit()) && model.getRpmLimit() > 0)
                .filter(model -> NullUtil.isNotNull(model.getTpmLimit()) && model.getTpmLimit() > 0)
                .toList();
        return routingStrategy.order(matched);
    }
}
