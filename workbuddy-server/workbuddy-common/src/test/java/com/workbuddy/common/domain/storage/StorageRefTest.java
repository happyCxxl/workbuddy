package com.workbuddy.common.domain.storage;

import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.enums.storage.StorageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 存储口径单测。
 *
 * <p>守护 {@code of}/{@code type}：档案两列原样搬运、类型码按枚举解析、未知码返回 null；
 * 解析错会让读取路由到错误后端。
 */
class StorageRefTest {

    /**
     * 档案 → 口径：类型码与数据源原样搬运。
     *
     * <p>守护 {@code of}：漏列会让读取回落到错误后端。
     */
    @Test
    void ofShouldCarryArchiveColumns() {
        WbFileObject row = new WbFileObject();
        row.setStorageType(StorageType.MINIO.getCode());
        row.setStorageSourceId(5L);

        StorageRef ref = StorageRef.of(row);

        assertEquals(StorageType.MINIO.getCode(), ref.storageType());
        assertEquals(5L, ref.sourceId());
        assertEquals(StorageType.MINIO, ref.type());
    }

    /**
     * 类型码为空或未知：{@code type()} 返回 null。
     *
     * <p>守护 {@code type}：未知码不能默认成某后端。
     */
    @Test
    void typeShouldReturnNullForUnknownCode() {
        assertNull(new StorageRef(null, 1L).type());
        assertNull(new StorageRef("UNKNOWN", 1L).type());
    }
}
