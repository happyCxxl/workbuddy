package com.workbuddy.filecenter.config;

import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件服务配置：数据源首次初始化的缺省来源。
 *
 * <p>{@link #storageType} 指明的类型在数据源表为空时决定哪一行置为当前启用，表里没有可用行时
 * 按它回落注册一条内置定义。{@link #minio} 与 {@link #local} 是两类数据源的缺省连接参数，
 * 只在 seed 与回落时读取；连接参数的实际来源是 wb_storage_source.config_json。
 */
@Data
@Component
@ConfigurationProperties(prefix = "file-center")
public class FileCenterConfig {

    /** 首次初始化置为当前启用的存储类型（StorageType 枚举码：minio / local） */
    private String storageType = "minio";

    /** MinIO 数据源缺省参数 */
    private Minio minio = new Minio();

    /** 本地磁盘数据源缺省参数 */
    private Local local = new Local();

    /**
     * 首次初始化置为当前启用的存储类型。
     *
     * @return 存储类型
     * @throws KnowledgeException 码值为空或未知（40500）
     */
    public StorageType defaultType() {
        StorageType type = StorageType.of(storageType);
        ThrowUtil.throwIf(NullUtil.isNull(type), ErrorCode.SYSTEM_ERROR,
                "file-center.storage-type 取值非法: " + storageType);
        return type;
    }

    /** MinIO 数据源缺省参数。 */
    @Data
    public static class Minio {

        /** 服务端点 */
        private String endpoint = "http://localhost:9000";

        /** 访问密钥 */
        private String accessKey = "minioadmin";

        /** 私有密钥 */
        private String secretKey = "minioadmin";

        /** 文件对象桶名 */
        private String fileBucket = "files";

        /** 内容寻址对象桶名 */
        private String artifactBucket = "artifacts";
    }

    /** 本地磁盘数据源缺省参数。 */
    @Data
    public static class Local {

        /** 存储根目录（相对路径按进程工作目录解析，启动时打印解析后的绝对路径） */
        private String rootDir = "./data/file-center";

        /** 文件对象一级子目录名 */
        private String fileDir = "files";

        /** 内容寻址对象一级子目录名 */
        private String artifactDir = "artifacts";
    }
}
