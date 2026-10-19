package com.workbuddy.common.enums.base;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除标记码单测。
 *
 * <p>守护 {@code of}/{@code isDeleted} 的码值口径：0=正常、1=已删除；
 * 写反会把正常数据过滤掉或把已删数据查出来。
 */
class DelFlagTest {

    /**
     * 码值映射与空值兜底。
     *
     * <p>守护 {@code of}：空值或未知名返回 null，不能默认成正常。
     */
    @Test
    void ofShouldMapCodeAndReturnNullForUnknown() {
        assertEquals(DelFlag.NORMAL, DelFlag.of(DelFlag.Code.NORMAL));
        assertEquals(DelFlag.DELETED, DelFlag.of(DelFlag.Code.DELETED));
        assertNull(DelFlag.of(null));
        assertNull(DelFlag.of("9"));
    }

    /**
     * 删除判定只认 DELETED。
     *
     * <p>守护 {@code isDeleted}：空值与未知名都必须为 false。
     */
    @Test
    void isDeletedShouldOnlyAcceptDeletedCode() {
        assertTrue(DelFlag.isDeleted("1"));
        assertFalse(DelFlag.isDeleted("0"));
        assertFalse(DelFlag.isDeleted(null));
        assertFalse(DelFlag.isDeleted("9"));
    }
}
