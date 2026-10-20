package com.workbuddy.common.dto.response.task;

import com.fasterxml.jackson.annotation.JsonAnyGetter;

import java.util.Map;

/**
 * 环节统计 VO 的公共基类型：字段由各环节子类声明，空对象表示该环节没有可下发的统计。
 */
public class StageStatsVO {

    /**
     * 空基类没有固定字段，提供一个空的动态属性出口，让 Jackson 能把无统计环节序列化为 {@code {}}。
     *
     * @return 空 map
     */
    @JsonAnyGetter
    public Map<String, String> anyStats() {
        return Map.of();
    }
}
