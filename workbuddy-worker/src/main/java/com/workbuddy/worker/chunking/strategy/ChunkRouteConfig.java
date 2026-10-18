package com.workbuddy.worker.chunking.strategy;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.utils.NullUtil;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 某一路内容（body/table/image/fallback）的算法配置：算法键 + 该算法的专属参数（字符串存，读取时按需转换）。
 * 参数表在策略解析时由 {@link ChunkAlgorithmSpec} 补全默认值，运行时参数总是完整可读。
 */
@Data
public class ChunkRouteConfig {

    /** 算法键（见 {@link ChunkAlgorithm} 目录） */
    private String algorithm;

    /** 该算法专属参数（键值均为字符串；数值参数读取用 intParam） */
    private Map<String, String> params = new HashMap<>();

    /** 数值参数读取：缺失/非法回退默认值 */
    public int intParam(String key, int defaultValue) {
        String value = NullUtil.isNull(params) ? null : params.get(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 字符串参数读取：缺失回退默认值 */
    public String strParam(String key, String defaultValue) {
        String value = NullUtil.isNull(params) ? null : params.get(key);
        return StrUtil.isBlank(value) ? defaultValue : value;
    }
}
