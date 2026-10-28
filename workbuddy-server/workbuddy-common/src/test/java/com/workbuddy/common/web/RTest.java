package com.workbuddy.common.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 统一响应体单测。
 *
 * <p>守护 {@code ok}/{@code failed} 的码值契约：成功码 0、失败码 1、失败不带数据；
 * 前端按 code 分支，码值写错会把失败渲染成成功。
 */
class RTest {

    /**
     * 成功响应：code=0，默认与自定义提示语都可用，时间戳非空。
     *
     * <p>守护 {@code ok} 的两个重载：码值或默认文案错即失败。
     */
    @Test
    void okShouldCarrySuccessCodeAndData() {
        R<String> withDefault = R.ok("data");
        assertEquals(R.SUCCESS, withDefault.getCode());
        assertEquals("操作成功", withDefault.getMsg());
        assertEquals("data", withDefault.getData());
        assertNotEquals(null, withDefault.getTimestamp());

        R<String> withMessage = R.ok("data", "已保存");
        assertEquals("已保存", withMessage.getMsg());
    }

    /**
     * 失败响应：code=1，数据为空。
     *
     * <p>守护 {@code failed}：失败仍带数据会让前端误以为有结果。
     */
    @Test
    void failedShouldCarryFailureCodeAndNoData() {
        R<String> failed = R.failed("参数错误");

        assertEquals(R.FAILURE, failed.getCode());
        assertEquals("参数错误", failed.getMsg());
        assertNull(failed.getData());
        assertNotEquals(null, failed.getTimestamp());
    }

    /**
     * 全字段构造与无参构造（反序列化）都可用。
     *
     * <p>守护 {@code R} 的构造契约：无参构造缺失会让 Jackson 反序列化失败。
     */
    @Test
    void constructorsShouldCarryAllFields() {
        R<Integer> full = new R<>(R.SUCCESS, "ok", 7, 123L);
        assertEquals(R.SUCCESS, full.getCode());
        assertEquals("ok", full.getMsg());
        assertEquals(7, full.getData());
        assertEquals(123L, full.getTimestamp());

        R<Integer> empty = new R<>();
        empty.setCode(R.FAILURE);
        empty.setMsg("x");
        empty.setData(1);
        empty.setTimestamp(9L);
        assertEquals(R.FAILURE, empty.getCode());
        assertEquals("x", empty.getMsg());
        assertEquals(1, empty.getData());
        assertEquals(9L, empty.getTimestamp());
    }
}
