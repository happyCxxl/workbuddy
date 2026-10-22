package com.workbuddy.model.route;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 直通策略：保持注册表顺序；M2 引入容量/延迟打分后由新策略替换。
 */
@Component
public class FirstMatchStrategy implements RoutingStrategy {

    /**
     * 保持候选原顺序。
     *
     * @param candidates 候选
     * @return 原顺序副本
     */
    @Override
    public List<ModelDescriptor> order(List<ModelDescriptor> candidates) {
        return List.copyOf(candidates);
    }
}
