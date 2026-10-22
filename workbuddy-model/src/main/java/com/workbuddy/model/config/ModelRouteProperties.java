package com.workbuddy.model.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型路由配置（前缀 `workbuddy.model.route`）：冷却、slow start、熔断阈值与尝试上限。
 *
 * <p>字段默认值即生产缺省；配置面在 `config/workbuddy-model.yml` 维护，启动期做值域校验。
 */
@Data
@Component
@ConfigurationProperties(prefix = "workbuddy.model.route")
public class ModelRouteProperties {

    /** 冷却时长（秒）：429 / 厂商不健康后该模型暂停被选中 */
    private long cooldownSeconds = 60;

    /** slow start 窗口（秒）：新装载/恢复的模型按比例放行 */
    private long slowStartSeconds = 45;

    /** 熔断失败率阈值（0~1，M3 使用） */
    private double errorTriggerRatio = 0.05;

    /** 熔断统计时间窗（秒，M3 使用） */
    private long errorWindowSeconds = 60;

    /** 一次请求最多尝试几个模型 */
    private int maxAttempts = 3;
}
