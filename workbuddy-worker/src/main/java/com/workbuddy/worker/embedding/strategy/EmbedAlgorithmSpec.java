package com.workbuddy.worker.embedding.strategy;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.embedding.EmbedProperties;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 向量化策略规格：默认补齐（normalize）/ 保存校验（validate）。
 * biz 保存校验与前端表单共用本口径。
 *
 * @author cxxl
 */
public final class EmbedAlgorithmSpec {

    private EmbedAlgorithmSpec() {
    }

    /** docTemplate 允许的占位符 */
    private static final Set<String> DOC_PLACEHOLDERS =
            Set.of("{content}", "{titlePath}", "{contentType}", "{tableRef}");

    /** queryTemplate 允许的占位符 */
    private static final Set<String> QUERY_PLACEHOLDERS = Set.of("{query}");

    // ---------------- 默认补齐（解析时） ----------------

    /**
     * 解析后补全：模板/批量/执行口径补默认。
     */
    public static EmbedStrategy normalize(EmbedStrategy strategy, EmbedProperties properties) {
        if (StrUtil.isBlank(strategy.getDocTemplate())) {
            strategy.setDocTemplate("{content}");
        }
        if (StrUtil.isBlank(strategy.getQueryTemplate())) {
            strategy.setQueryTemplate("{query}");
        }
        if (NullUtil.isNull(strategy.getBatchSize())) {
            strategy.setBatchSize(properties.getBatchSize());
        }
        if (NullUtil.isNull(strategy.getTimeoutMs())) {
            strategy.setTimeoutMs(properties.getTimeoutMs());
        }
        if (NullUtil.isNull(strategy.getMaxRetries())) {
            strategy.setMaxRetries(properties.getMaxRetries());
        }
        strategy.setCacheEnabled(enumOr(strategy.getCacheEnabled(), properties.getCacheEnabled().name()));
        strategy.setIncludeParent(enumOr(strategy.getIncludeParent(), properties.getIncludeParent().name()));
        strategy.setSkipEmpty(enumOr(strategy.getSkipEmpty(), properties.getSkipEmpty().name()));
        return strategy;
    }

    /** 开关值规范：空回退默认，非空统一大写 */
    private static String enumOr(String value, String defaultValue) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    // ---------------- 保存校验（biz 调用；返回 null = 通过，否则为错误信息） ----------------

    /** 校验配置 JSON；非法返回错误信息，合法返回 null。config 应为模型/模板/批量/执行口径结构（无 type/name/version 顶层键）。 */
    public static String validate(Map<String, Object> config) {
        if (NullUtil.isNull(config)) {
            return "配置不能为空";
        }
        String model = String.valueOf(config.getOrDefault("model", ""));
        if (StrUtil.isBlank(model)) {
            return "模型不能为空";
        }
        String templateError = validateTemplate(config.get("docTemplate"), "{content}", DOC_PLACEHOLDERS,
                "docTemplate");
        if (NullUtil.isNotNull(templateError)) {
            return templateError;
        }
        templateError = validateTemplate(config.get("queryTemplate"), "{query}", QUERY_PLACEHOLDERS, "queryTemplate");
        if (NullUtil.isNotNull(templateError)) {
            return templateError;
        }
        String rangeError = validateRange(config, "batchSize", 1, 128);
        if (NullUtil.isNotNull(rangeError)) {
            return rangeError;
        }
        rangeError = validateRange(config, "timeoutMs", 1000, 120000);
        if (NullUtil.isNotNull(rangeError)) {
            return rangeError;
        }
        rangeError = validateRange(config, "maxRetries", 0, 5);
        if (NullUtil.isNotNull(rangeError)) {
            return rangeError;
        }
        for (String key : new String[] { "cacheEnabled", "includeParent", "skipEmpty" }) {
            String enumError = validateEnum(config, key);
            if (NullUtil.isNotNull(enumError)) {
                return enumError;
            }
        }
        return null; // 未知键透传不报错（前向兼容）
    }

    /** 模板校验：非空时必含 required 占位符且全部占位符在白名单内 */
    private static String validateTemplate(Object value, String required, Set<String> allowed, String field) {
        if (NullUtil.isNull(value) || StrUtil.isBlank(String.valueOf(value))) {
            return null; // 空走默认，不校验
        }
        String template = String.valueOf(value);
        if (!template.contains(required)) {
            return field + " 必须包含 " + required + " 占位符";
        }
        for (String placeholder : extractPlaceholders(template)) {
            if (!allowed.contains(placeholder)) {
                return field + " 占位符不在白名单: " + placeholder;
            }
        }
        return null;
    }

    /** 提取 {xxx} 占位符（去重） */
    private static Set<String> extractPlaceholders(String template) {
        Set<String> placeholders = new LinkedHashSet<>();
        int from = 0;
        while (true) {
            int open = template.indexOf('{', from);
            if (open < 0) {
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                break;
            }
            placeholders.add(template.substring(open, close + 1));
            from = close + 1;
        }
        return placeholders;
    }

    private static String validateRange(Map<String, Object> config, String key, int min, int max) {
        Object value = config.get(key);
        if (NullUtil.isNull(value)) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            if (parsed < min || parsed > max) {
                return key + " 越界（允许 " + min + "~" + max + "）";
            }
            return null;
        } catch (NumberFormatException e) {
            return key + " 必须是整数";
        }
    }

    private static String validateEnum(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (NullUtil.isNull(value)) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (!EmbedStrategy.ON.equalsIgnoreCase(text) && !EmbedStrategy.OFF.equalsIgnoreCase(text)) {
            return key + " 只能是 ON/OFF";
        }
        return null;
    }
}
