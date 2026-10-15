package com.workbuddy.filecenter.config;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.filecenter.provider.StorageSourceDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置存储数据源：按 {@link FileCenterConfig} 的两段参数组装数据源定义。
 *
 * <p>数据源表为空时用两条内置定义 seed 两行（MinIO / 本地磁盘）；表里没有可用行时用配置指明的
 * 那一条回落注册。定义里的主键由这里生成，落库与注册共用同一个 ID。
 *
 * @author cxxl
 */
public final class FileCenterDefaults {

    /** MinIO 内置数据源名称 */
    public static final String MINIO_NAME = "MinIO";

    /** 本地磁盘内置数据源名称 */
    public static final String LOCAL_NAME = "本地磁盘";

    private FileCenterDefaults() {
    }

    /**
     * 两条内置数据源定义（顺序：MinIO、本地磁盘）。
     *
     * @param config 文件服务配置
     * @return 数据源定义清单
     */
    public static List<StorageSourceDef> seedDefs(FileCenterConfig config) {
        return List.of(minioDef(config), localDef(config));
    }

    /**
     * 配置指明的类型对应的内置数据源定义。
     *
     * @param config 文件服务配置
     * @param type   存储类型
     * @return 数据源定义
     */
    public static StorageSourceDef defOfType(FileCenterConfig config, StorageType type) {
        return type == StorageType.MINIO ? minioDef(config) : localDef(config);
    }

    /** MinIO 内置定义：端点、凭据与两个桶名 */
    private static StorageSourceDef minioDef(FileCenterConfig config) {
        FileCenterConfig.Minio minio = config.getMinio();
        Map<String, String> params = new LinkedHashMap<>();
        params.put(StorageSourceDef.KEY_ENDPOINT, minio.getEndpoint());
        params.put(StorageSourceDef.KEY_ACCESS_KEY, minio.getAccessKey());
        params.put(StorageSourceDef.KEY_SECRET_KEY, minio.getSecretKey());
        params.put(StorageSourceDef.KEY_FILE_BUCKET, minio.getFileBucket());
        params.put(StorageSourceDef.KEY_ARTIFACT_BUCKET, minio.getArtifactBucket());
        return new StorageSourceDef(IdWorker.getId(), MINIO_NAME, StorageType.MINIO,
                StorageSourceDef.normalize(params));
    }

    /** 本地磁盘内置定义：存储根与两个目录名 */
    private static StorageSourceDef localDef(FileCenterConfig config) {
        FileCenterConfig.Local local = config.getLocal();
        Map<String, String> params = new LinkedHashMap<>();
        params.put(StorageSourceDef.KEY_ROOT_DIR, local.getRootDir());
        params.put(StorageSourceDef.KEY_FILE_DIR, local.getFileDir());
        params.put(StorageSourceDef.KEY_ARTIFACT_DIR, local.getArtifactDir());
        return new StorageSourceDef(IdWorker.getId(), LOCAL_NAME, StorageType.LOCAL,
                StorageSourceDef.normalize(params));
    }
}
