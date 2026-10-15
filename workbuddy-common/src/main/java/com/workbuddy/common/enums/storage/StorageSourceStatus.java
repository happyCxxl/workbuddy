package com.workbuddy.common.enums.storage;

import com.workbuddy.common.utils.NullUtil;
import lombok.Getter;

/**
 * 存储数据源状态：决定该数据源是否接入运行时。
 *
 * <p>落库为码值字符串（wb_storage_source.status）；停用的数据源不注册进存储路由，
 * 读取落在它上面的对象会按「存储后端未配置」报错。
 *
 * @author cxxl
 */
@Getter
public enum StorageSourceStatus {

    /** 启用：接入存储路由，可作为当前启用的数据源 */
    ENABLED("ENABLED"),

    /** 停用：不接入存储路由，也不参与当前启用 */
    DISABLED("DISABLED");

    private final String code;

    StorageSourceStatus(String code) {
        this.code = code;
    }

    /**
     * 按码值解析状态（大小写不敏感）。
     *
     * @param code 状态码值（可空/未知）
     * @return 匹配的状态；空值与未知码值返回 null
     */
    public static StorageSourceStatus of(String code) {
        if (NullUtil.isNull(code)) {
            return null;
        }
        for (StorageSourceStatus status : values()) {
            if (status.code.equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        return null;
    }
}
