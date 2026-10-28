package com.workbuddy.common.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具类（基于 hutool，供 biz / worker 共用）。
 * 注意：hutool 默认把 LocalDateTime 序列化成时间戳数字，本类统一配置为可读时间字符串。
 */
public final class JsonUtil {

    /** 默认 JSON 配置：LocalDateTime 输出 "yyyy-MM-dd HH:mm:ss" */
    private static final JSONConfig DEFAULT_CONFIG = JSONConfig.create()
            .setDateFormat("yyyy-MM-dd HH:mm:ss");

    private JsonUtil() {
    }

    /**
     * 对象转 JSON 字符串。
     *
     * @param obj 对象
     * @return JSON 字符串；入参为 null 时为 null
     */
    public static String toJsonStr(Object obj) {
        if (NullUtil.isNull(obj)) {
            return null;
        }
        return JSONUtil.toJsonStr(obj, DEFAULT_CONFIG);
    }

    /**
     * JSON 字符串转对象。
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   目标类型参数
     * @return 目标对象；入参为 null 或空串时为 null
     */
    public static <T> T toObject(String json, Class<T> clazz) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        return JSONUtil.toBean(json, clazz);
    }

    /**
     * JSON 对象字符串转 Map（策略配置快照等动态结构）。
     *
     * @param json JSON 字符串
     * @return 键值映射；入参为 null 或空串时为空 Map
     */
    public static Map<String, Object> toMap(String json) {
        if (StrUtil.isBlank(json)) {
            return new java.util.HashMap<>();
        }
        return JSONUtil.toBean(json, Map.class);
    }

    /**
     * JSON 数组字符串转 List（检索运行记录快照回放等）。
     *
     * @param json        JSON 数组字符串
     * @param elementType 元素类型
     * @param <T>         元素类型参数
     * @return 元素列表；入参为 null 或空串时为空列表
     */
    public static <T> List<T> toList(String json, Class<T> elementType) {
        if (StrUtil.isBlank(json)) {
            return new ArrayList<>();
        }
        return JSONUtil.toList(json, elementType);
    }
}
