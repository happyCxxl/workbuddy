package com.workbuddy.infra.storage;

import com.workbuddy.common.enums.storage.StorageType;

import java.io.InputStream;

/**
 * 存储提供者：单一存储后端的原始对象写入、读取与存在性。
 *
 * <p>实现按 {@link #type()} 交给 StorageRouter 管理：当前启用的数据源在标记为当前启用时
 * {@link #initialize()}（建桶或建存储根目录），其余数据源首次用到才初始化。
 */
public interface StorageProvider {

    /** 存储类型 */
    StorageType type();

    /** 写入对象 */
    void put(String bucket, String key, InputStream in, long size, String contentType);

    /** 打开对象流 */
    InputStream get(String bucket, String key);

    /** 对象是否存在 */
    boolean exists(String bucket, String key);

    /** 首次使用前的准备：建桶或建存储根目录，不可用时抛异常 */
    default void initialize() {
    }
}
