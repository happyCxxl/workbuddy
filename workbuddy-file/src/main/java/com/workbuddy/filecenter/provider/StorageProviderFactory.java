package com.workbuddy.filecenter.provider;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;

import java.util.List;

/**
 * 存储提供者工厂：按数据源定义构造对应类型的提供者实例。
 *
 * <p>路由持有的实例与连接测试的临时实例都从这里出，构造参数一律取定义里的参数；
 * 必填参数不齐时按「存储后端未配置」拒绝，不构造半可用的实例。
 */
public final class StorageProviderFactory {

    private StorageProviderFactory() {
    }

    /**
     * 按定义构造提供者（不发网络请求，建桶或建根目录见 {@link StorageProvider#initialize()}）。
     *
     * @param def 数据源定义
     * @return 该类型的提供者
     * @throws KnowledgeException 必填参数不齐（40455）
     */
    public static StorageProvider create(StorageSourceDef def) {
        List<String> missing = StorageSourceDef.missingKeys(def);
        ThrowUtil.throwIf(!missing.isEmpty(), ErrorCode.STORAGE_BACKEND_UNCONFIGURED,
                "存储数据源参数不完整: name=" + def.name() + ", 缺少 " + String.join(",", missing));
        return switch (def.type()) {
            case MINIO -> new MinioStorageProvider(def);
            case LOCAL -> new LocalStorageProvider(def);
        };
    }
}
