package com.workbuddy.model.service.support.route;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.enums.model.ModelStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.ModelCatalogPort;
import com.workbuddy.model.ModelRef;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.service.support.capacity.ModelCapacityGuard;
import com.workbuddy.model.service.support.capacity.ModelCooldownStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 候选路由：能力 → 启用 → 模型名过滤 → 限额已配 → 未冷却 → 容量/延迟打分 → 策略排序。
 *
 * <p>容量分 = 双桶剩余比例最小值 × slow start 系数（恢复期模型排后）；延迟 EWMA 只在本实例内。
 */
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final ModelCatalogPort modelCatalogPort;

    private final RoutingStrategy routingStrategy;

    private final ModelCapacityGuard modelCapacityGuard;

    private final ModelCooldownStore modelCooldownStore;

    private final RouteStateStore routeStateStore;

    private final ModelRouteProperties modelRouteProperties;

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
                .filter(model -> !modelCooldownStore.coolingDown(refOf(model)))
                .toList();
        List<RouteCandidate> ranked = matched.stream().map(this::toCandidate).toList();
        return routingStrategy.order(ranked).stream().map(RouteCandidate::model).toList();
    }

    /**
     * 选本次尝试的模型：在候选顺序上跳过已尝试过的模型，取第一个。
     *
     * @param capability     能力
     * @param modelKeyFilter 精确模型名过滤，可空
     * @param tried          本次请求已尝试过的模型
     * @return 选中的模型；无可用候选时为空
     */
    public ModelDescriptor select(ModelCapability capability, String modelKeyFilter, Set<ModelRef> tried) {
        return candidates(capability, modelKeyFilter).stream()
                .filter(model -> NullUtil.isNull(tried) || !tried.contains(refOf(model)))
                .findFirst()
                .orElse(null);
    }

    /** 目录行 → 带容量与延迟的候选 */
    private RouteCandidate toCandidate(ModelDescriptor model) {
        ModelRef ref = refOf(model);
        CapacitySnapshot snapshot = modelCapacityGuard.snapshot(ref, model.getRpmLimit(), model.getTpmLimit());
        double slowStart = routeStateStore.slowStartFactor(ref, modelRouteProperties.getSlowStartSeconds());
        return new RouteCandidate(model, snapshot.score() * slowStart, routeStateStore.latencyMillis(ref));
    }

    private ModelRef refOf(ModelDescriptor model) {
        return new ModelRef(model.getVendorCode(), model.getModelKey());
    }
}
