package com.workbuddy.common.domain.storage;

import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.enums.storage.StorageType;

/**
 * 档案行的存储口径：类型码值（展示与降级）+ 数据源实例 ID（定位后端）。
 *
 * <p>判定「这条链落在哪个数据源」时取它：码值未知或数据源为空表示口径取不到，
 * 判定方按「取不到不拦」处理，读取阶段再按对象位置给出明确错误码。
 *
 * @param storageType 存储类型码值（StorageType 枚举码，可空/未知）
 * @param sourceId    数据源实例 ID（wb_storage_source.id，可空）
 */
public record StorageRef(String storageType, Long sourceId) {

    /** 文件档案行的存储口径 */
    public static StorageRef of(WbFileObject row) {
        return new StorageRef(row.getStorageType(), row.getStorageSourceId());
    }

    /** 存储类型（码值为空或未知时返回 null） */
    public StorageType type() {
        return StorageType.of(storageType);
    }
}
