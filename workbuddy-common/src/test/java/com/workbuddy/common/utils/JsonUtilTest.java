package com.workbuddy.common.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 工具口径单测。
 *
 * <p>守护 {@code toJsonStr}/{@code toObject}/{@code toMap}/{@code toList} 的四个契约：
 * 空入参返回 null 或空容器、时间统一成可读字符串（不是时间戳）、动态结构落 Map、数组落 List。
 */
class JsonUtilTest {

    /** 夹具：带时间字段的值对象 */
    public static class Stamp {
        /** 名称 */
        public String name;
        /** 发生时间 */
        public LocalDateTime at;
    }

    /**
     * 对象转 JSON：空对象返回 null，时间按可读格式输出。
     *
     * <p>守护 {@code toJsonStr}：空入参返回 "null" 字符串或时间戳格式都算错。
     */
    @Test
    void toJsonStrShouldRenderReadableTimeAndNullForEmpty() {
        assertNull(JsonUtil.toJsonStr(null));

        Stamp stamp = new Stamp();
        stamp.name = "a";
        stamp.at = LocalDateTime.of(2026, 10, 9, 10, 0, 0);
        String json = JsonUtil.toJsonStr(stamp);

        assertTrue(json.contains("\"at\":\"2026-10-09 10:00:00\""), json);
        assertTrue(json.contains("\"name\":\"a\""), json);
    }

    /**
     * JSON 转对象：空白返回 null，正常 JSON 逐字段还原。
     *
     * <p>守护 {@code toObject} 的空入参兜底与反序列化。
     */
    @Test
    void toObjectShouldReturnNullForBlankAndParseFields() {
        assertNull(JsonUtil.toObject(null, Stamp.class));
        assertNull(JsonUtil.toObject(" ", Stamp.class));

        Stamp stamp = JsonUtil.toObject("{\"name\":\"b\"}", Stamp.class);
        assertEquals("b", stamp.name);
    }

    /**
     * JSON 转 Map：空白返回空 Map（可编辑），对象字段落键值。
     *
     * <p>守护 {@code toMap} 的空入参口径：返回 null 会让调用方空指针。
     */
    @Test
    void toMapShouldReturnEmptyForBlankAndParseKeys() {
        Map<String, Object> empty = JsonUtil.toMap(null);
        assertTrue(empty.isEmpty());
        empty.put("k", "v");

        Map<String, Object> map = JsonUtil.toMap("{\"a\":1}");
        assertEquals(1, map.get("a"));
    }

    /**
     * JSON 数组转 List：空白返回空列表，元素逐项还原。
     *
     * <p>守护 {@code toList} 的空入参口径与元素类型。
     */
    @Test
    void toListShouldReturnEmptyForBlankAndParseElements() {
        assertTrue(JsonUtil.toList("", Stamp.class).isEmpty());

        List<Stamp> list = JsonUtil.toList("[{\"name\":\"c\"}]", Stamp.class);
        assertEquals(1, list.size());
        assertEquals("c", list.get(0).name);
    }
}
