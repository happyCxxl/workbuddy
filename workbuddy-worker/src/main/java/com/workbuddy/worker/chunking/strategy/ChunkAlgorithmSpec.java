package com.workbuddy.worker.chunking.strategy;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkParam;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.ChunkProperties;

import cn.hutool.core.util.StrUtil;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 切片算法规格：默认算法 / 支持集判定 / 参数默认补齐 / 保存配置校验。
 * 算法目录与支持状态在 {@link ChunkAlgorithm}（单一事实源）；biz 保存校验、前端置灰均与该口径一致。
 */
public final class ChunkAlgorithmSpec {

    private ChunkAlgorithmSpec() {
    }

    // ---------------- 默认算法与支持集 ----------------

    /** 各路默认算法。 */
    public static ChunkAlgorithm defaultAlgorithm(ChunkRoute route) {
        return switch (route) {
            case BODY -> ChunkAlgorithm.BODY_PARAGRAPH_AGGREGATE;
            case TABLE -> ChunkAlgorithm.TABLE_ROW_SLICE;
            case IMAGE -> ChunkAlgorithm.IMAGE_CAPTION_PLACEHOLDER;
            case FALLBACK -> ChunkAlgorithm.FALLBACK_RECURSIVE;
        };
    }

    /** 算法键是否在当前支持集（biz 保存校验 + 前端置灰依据）。 */
    public static boolean isSupported(ChunkRoute route, String algorithmKey) {
        ChunkAlgorithm algorithm = ChunkAlgorithm.of(route, algorithmKey);
        return NullUtil.isNotNull(algorithm) && algorithm.supported();
    }

    // ---------------- 参数默认补齐与流程层默认 ----------------

    /**
     * 解析后补全：缺省路由用默认算法、缺省参数用全局默认（ChunkProperties，application.yml 同名 kebab-case 键）、流程层键补默认。
     */
    public static ChunkStrategy normalize(ChunkStrategy strategy, ChunkProperties properties) {
        if (NullUtil.isNull(strategy.getRoutes())) {
            strategy.setRoutes(new HashMap<>());
        }
        if (NullUtil.isNull(strategy.getPipeline())) {
            strategy.setPipeline(new HashMap<>());
        }
        for (ChunkRoute route : ChunkRoute.values()) {
            normalizeRoute(strategy, route, properties);
        }

        Map<String, String> pipeline = strategy.getPipeline();
        pipeline.putIfAbsent(PipelineKey.TITLE_PATH_MAX_LEVEL.key(), String.valueOf(properties.getTitlePathMaxLevel()));
        pipeline.putIfAbsent(PipelineKey.PARENT_CHILD.key(), ChunkStrategy.ON);
        pipeline.putIfAbsent(PipelineKey.TABLE_IN_BODY_FLOW.key(), ChunkStrategy.OFF);
        pipeline.putIfAbsent(PipelineKey.MIN_MERGE_LEN.key(), String.valueOf(properties.getMinMergeLen()));
        pipeline.putIfAbsent(PipelineKey.STRUCTURE_OVERLAP.key(), String.valueOf(properties.getStructureOverlap()));
        pipeline.putIfAbsent(PipelineKey.TITLE_IN_CONTENT.key(), ChunkStrategy.ON);
        return strategy;
    }

    private static void normalizeRoute(ChunkStrategy strategy, ChunkRoute route, ChunkProperties properties) {
        ChunkRouteConfig config = strategy.getRoutes().get(route.key());
        if (NullUtil.isNull(config)) {
            config = new ChunkRouteConfig();
            strategy.getRoutes().put(route.key(), config);
        }
        if (StrUtil.isBlank(config.getAlgorithm())) {
            config.setAlgorithm(defaultAlgorithm(route).key());
        }
        if (NullUtil.isNull(config.getParams())) {
            config.setParams(new HashMap<>());
        }
        defaultParams(route, config.getAlgorithm(), properties).forEach(config.getParams()::putIfAbsent);
    }

    /** 各算法默认参数（值源 = ChunkProperties 全局默认，application.yml 同名 kebab-case 键）；算法键未识别时不补参数。 */
    public static Map<String, String> defaultParams(ChunkRoute route, String algorithmKey, ChunkProperties properties) {
        Map<String, String> defaults = new LinkedHashMap<>();
        ChunkAlgorithm algorithm = ChunkAlgorithm.of(route, algorithmKey);
        if (NullUtil.isNull(algorithm)) {
            return defaults;
        }
        switch (algorithm) {
            case BODY_PARAGRAPH_AGGREGATE, BODY_STRUCTURE_HYBRID, BODY_SENTENCE_AGGREGATE -> {
                defaults.put(ChunkParamKeys.TARGET_MAX_LEN, String.valueOf(properties.getTargetMaxLen()));
                defaults.put(ChunkParamKeys.SOFT_MAX_LEN, String.valueOf(properties.getSoftMaxLen()));
            }
            case BODY_TITLE_BOUNDARY ->
                defaults.put(ChunkParamKeys.MAX_LEN, String.valueOf(properties.getTitleBoundaryMaxLen()));
            case BODY_FIXED_WINDOW -> {
                defaults.put(ChunkParamKeys.LEN, String.valueOf(properties.getBodyWindowLen()));
                defaults.put(ChunkParamKeys.OVERLAP, String.valueOf(properties.getBodyWindowOverlap()));
            }
            case TABLE_ROW_SLICE -> {
                defaults.put(ChunkParamKeys.GROUP_THRESHOLD, String.valueOf(properties.getTableRowGroupThreshold()));
                defaults.put(ChunkParamKeys.GROUP_SIZE, String.valueOf(properties.getTableRowGroupSize()));
            }
            case TABLE_ROW_GROUP -> {
                defaults.put(ChunkParamKeys.GROUP_SIZE, String.valueOf(properties.getRowGroupSize()));
                defaults.put(ChunkParamKeys.MAX_LEN, String.valueOf(properties.getRowGroupMaxLen()));
            }
            case TABLE_WHOLE ->
                defaults.put(ChunkParamKeys.MAX_LEN, String.valueOf(properties.getWholeTableMaxLen()));
            case TABLE_CONTEXT_MERGED -> {
                defaults.put(ChunkParamKeys.LEAD_MAX_LEN, String.valueOf(properties.getContextLeadMaxLen()));
                defaults.put(ChunkParamKeys.GROUP_THRESHOLD, String.valueOf(properties.getTableRowGroupThreshold()));
                defaults.put(ChunkParamKeys.GROUP_SIZE, String.valueOf(properties.getTableRowGroupSize()));
            }
            case FALLBACK_RECURSIVE, FALLBACK_FIXED_WINDOW -> {
                defaults.put(ChunkParamKeys.LEN, String.valueOf(properties.getRecursiveLen()));
                defaults.put(ChunkParamKeys.OVERLAP, String.valueOf(properties.getRecursiveOverlap()));
            }
            default -> {
            }
        }
        return defaults;
    }

    // ---------------- 保存校验（biz 调用；返回 null = 通过，否则为错误信息） ----------------

    /**
     * 校验配置 JSON 结构；非法返回错误信息，合法返回 null。configSnapshot 应为
     * {"routes":{...},"pipeline":{...}} 结构（无 type/name/version 顶层键）。
     */
    @SuppressWarnings("unchecked")
    public static String validate(Map<String, Object> config) {
        if (NullUtil.isNull(config)) {
            return "配置不能为空";
        }
        Object routesObj = config.get("routes");
        if (NullUtil.isNotNull(routesObj) && !(routesObj instanceof Map)) {
            return "routes 必须是对象";
        }
        Map<String, Object> routes = (Map<String, Object>) (NullUtil.isNull(routesObj) ? Map.of() : routesObj);
        for (ChunkRoute route : ChunkRoute.values()) {
            Object routeObj = routes.get(route.key());
            if (NullUtil.isNotNull(routeObj)) {
                if (!(routeObj instanceof Map)) {
                    return "routes." + route.key() + " 必须是对象";
                }
                Map<String, Object> routeMap = (Map<String, Object>) routeObj;
                String algorithm = String.valueOf(routeMap.getOrDefault("algorithm", ""));
                if (StrUtil.isNotBlank(algorithm) && !isSupported(route, algorithm)) {
                    return "算法尚未支持: " + route.key() + "." + algorithm;
                }
                String paramError = validateParams(route, algorithm, routeMap);
                if (NullUtil.isNotNull(paramError)) {
                    return paramError;
                }
            }
        }
        // 不变量
        return validateInvariants(config);
    }

    private static String validateParams(ChunkRoute route, String algorithmKey, Map<String, Object> routeMap) {
        Object paramsObj = routeMap.get("params");
        if (NullUtil.isNotNull(paramsObj) && !(paramsObj instanceof Map)) {
            return "routes." + route.key() + ".params 必须是对象";
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) (NullUtil.isNull(paramsObj) ? Map.of() : paramsObj);
        // 允许的键与范围：单一事实源在 ChunkParam（按算法索引）
        Map<String, ChunkParam> allowed = new LinkedHashMap<>();
        for (ChunkParam param : ChunkParam.ofAlgorithm(ChunkAlgorithm.of(route, algorithmKey))) {
            allowed.put(param.key(), param);
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            ChunkParam param = allowed.get(entry.getKey());
            if (NullUtil.isNull(param)) {
                // 未知参数键透传不报错（前向兼容）
                continue;
            }
            try {
                int value = Integer.parseInt(String.valueOf(entry.getValue()).trim());
                if (value < param.min() || value > param.max()) {
                    return "参数越界: " + route.key() + "." + entry.getKey()
                            + "（允许 " + param.min() + "~" + param.max() + "）";
                }
            } catch (NumberFormatException e) {
                return "参数必须是整数: " + route.key() + "." + entry.getKey();
            }
        }
        return null;
    }

    /** 跨路由/流程层不变量校验 */
    @SuppressWarnings("unchecked")
    private static String validateInvariants(Map<String, Object> config) {
        Object pipelineObj = config.get("pipeline");
        Map<String, Object> pipeline = pipelineObj instanceof Map ? (Map<String, Object>) pipelineObj : Map.of();
        String tableInBodyFlow = String.valueOf(pipeline.getOrDefault(PipelineKey.TABLE_IN_BODY_FLOW.key(), ""));
        Object routesObj = config.get("routes");
        Map<String, Object> routes = routesObj instanceof Map ? (Map<String, Object>) routesObj : Map.of();
        Object tableObj = routes.get(ChunkRoute.TABLE.key());
        String tableAlgorithm = tableObj instanceof Map
                ? String.valueOf(((Map<String, Object>) tableObj).getOrDefault("algorithm", "")) : "";
        if (ChunkStrategy.ON.equalsIgnoreCase(tableInBodyFlow)
                && ChunkAlgorithm.TABLE_CONTEXT_MERGED.key().equals(tableAlgorithm)) {
            return "表格并入正文流与表+引导段落不可同时启用";
        }
        // 数值不变量：softMaxLen ≥ targetMaxLen（正文三算法）；fallback.overlap < fallback.len
        Object bodyObj = routes.get(ChunkRoute.BODY.key());
        if (bodyObj instanceof Map) {
            Map<String, Object> body = (Map<String, Object>) bodyObj;
            String algorithm = String.valueOf(body.getOrDefault("algorithm", ""));
            ChunkAlgorithm bodyAlgorithm = ChunkAlgorithm.of(ChunkRoute.BODY, algorithm);
            if (NullUtil.isNotNull(bodyAlgorithm) && Set.of(ChunkAlgorithm.BODY_PARAGRAPH_AGGREGATE,
                    ChunkAlgorithm.BODY_STRUCTURE_HYBRID, ChunkAlgorithm.BODY_SENTENCE_AGGREGATE)
                    .contains(bodyAlgorithm)) {
                Object paramsObj = body.get("params");
                Map<String, Object> params = paramsObj instanceof Map ? (Map<String, Object>) paramsObj : Map.of();
                int target = intOf(params.get("targetMaxLen"), Integer.MAX_VALUE);
                int soft = intOf(params.get("softMaxLen"), Integer.MIN_VALUE);
                if (target != Integer.MAX_VALUE && soft != Integer.MIN_VALUE && soft < target) {
                    return "软上限必须 ≥ 目标片长上限（softMaxLen ≥ targetMaxLen）";
                }
            }
        }
        Object fallbackObj = routes.get(ChunkRoute.FALLBACK.key());
        if (fallbackObj instanceof Map) {
            Map<String, Object> fallback = (Map<String, Object>) fallbackObj;
            Object paramsObj = fallback.get("params");
            Map<String, Object> params = paramsObj instanceof Map ? (Map<String, Object>) paramsObj : Map.of();
            int len = intOf(params.get("len"), Integer.MIN_VALUE);
            int overlap = intOf(params.get("overlap"), Integer.MAX_VALUE);
            if (len != Integer.MIN_VALUE && overlap != Integer.MAX_VALUE && overlap >= len) {
                return "兜底重叠必须小于兜底长度（overlap < len）";
            }
        }
        return null;
    }

    private static int intOf(Object value, int defaultValue) {
        if (NullUtil.isNull(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
