package com.workbuddy.infra.modelgateway;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关 data 段取值辅助（包内使用）：键非字符串的条目丢弃，缺失返回 null。
 */
final class GatewayDataSupport {

    private GatewayDataSupport() {
    }

    /**
     * 取 data 段中的 map 字段。
     *
     * @param data 网关 data 段
     * @param key  字段名
     * @return 字段值（map）；缺失或形态不符为 null
     */
    static Map<String, Object> mapOf(Map<?, ?> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String keyText) {
                result.put(keyText, entry.getValue());
            }
        }
        return result;
    }
}
