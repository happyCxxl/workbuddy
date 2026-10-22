package com.workbuddy.model.service.support.route;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;

/**
 * 路由候选：目录行 + 本次打分用到的容量与延迟。
 *
 * @param model         目录行
 * @param capacity      容量分（0~1，已乘 slow start 系数）
 * @param latencyMillis 延迟 EWMA（毫秒，未采样为 0）
 */
public record RouteCandidate(ModelDescriptor model, double capacity, long latencyMillis) {
}
