package com.workbuddy.common.enums.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 数据源状态码单测。
 *
 * <p>守护 {@code of} 的解析口径：大小写与首尾空白不敏感、空值与未知名返回 null；
 * 解析错会让停用数据源被当成启用。
 */
class StorageSourceStatusTest {

    /**
     * 状态解析：大小写/空白不敏感，未知返回 null。
     *
     * <p>守护 {@code of}：任一口径错即失败。
     */
    @Test
    void ofShouldParseIgnoreCaseAndTrim() {
        assertEquals(StorageSourceStatus.ENABLED, StorageSourceStatus.of("enabled"));
        assertEquals(StorageSourceStatus.DISABLED, StorageSourceStatus.of(" DISABLED "));
        assertNull(StorageSourceStatus.of(null));
        assertNull(StorageSourceStatus.of("UNKNOWN"));
    }
}
