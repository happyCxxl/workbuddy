package com.workbuddy.common.enums.storage;

import com.workbuddy.common.utils.NullUtil;
import lombok.Getter;

/**
 * 对象存储类型：记录里落库的后端标识，决定对象本体读到哪个后端取。
 *
 * <p>落库为码值字符串（`kb_file_object.storage_type` 与产物、切片、向量、头像表的同名列）。
 * 新增一种存储类型 = 加一个枚举常量 + 一个 StorageProvider 实现 + 一段配置。
 *
 * <p>{@link #of(String)} 对空值与未知码值返回 null：读路径据此报「存储后端未配置」，
 * 不回落成别的后端。
 *
 * @author cxxl
 */
@Getter
public enum StorageType {

    /** MinIO 对象存储（S3 兼容） */
    MINIO("minio"),

    /** 本地磁盘（桶名当存储根下的一级子目录） */
    LOCAL("local");

    private final String code;

    StorageType(String code) {
        this.code = code;
    }

    /**
     * 按码值解析存储类型（大小写不敏感）。
     *
     * @param code 存储类型码值（可空/未知）
     * @return 匹配的类型；空值与未知码值返回 null
     */
    public static StorageType of(String code) {
        if (NullUtil.isNull(code)) {
            return null;
        }
        for (StorageType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        return null;
    }
}
