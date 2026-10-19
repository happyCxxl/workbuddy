package com.workbuddy.infra.storage;

import com.workbuddy.common.enums.storage.StorageType;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

/**
 * MinIO 存储提供者：对象写入、读取与存在性。
 *
 * <p>端点与凭据取自数据源定义的参数；客户端在构造时建立（不发网络请求），
 * {@link #initialize()} 才连服务并确保文件桶与产物桶存在。
 */
@Slf4j
public class MinioStorageProvider implements StorageProvider {

    private final StorageSourceDef def;

    private final MinioClient minioClient;

    /**
     * 构造 MinIO 后端：端点与凭据取自数据源参数。
     *
     * @param def 数据源定义
     */
    public MinioStorageProvider(StorageSourceDef def) {
        this.def = def;
        this.minioClient = MinioClient.builder()
                .endpoint(def.param(StorageSourceDef.KEY_ENDPOINT))
                .credentials(def.param(StorageSourceDef.KEY_ACCESS_KEY),
                        def.param(StorageSourceDef.KEY_SECRET_KEY))
                .build();
    }

    /**
     * 存储类型。
     *
     * @return MinIO
     */
    @Override
    public StorageType type() {
        return StorageType.MINIO;
    }

    /**
     * 确保两个桶存在，重复调用无副作用。
     */
    @Override
    public void initialize() {
        ensureBucket(def.fileBucket());
        ensureBucket(def.artifactBucket());
        log.info("MinIO 桶就绪: {}/{}（数据源 {}, 端点 {}）", def.fileBucket(), def.artifactBucket(),
                def.name(), def.param(StorageSourceDef.KEY_ENDPOINT));
    }

    /**
     * 写入对象。
     *
     * @param bucket      桶名
     * @param key         对象名
     * @param in          内容流
     * @param size        内容字节数
     * @param contentType 内容类型
     */
    @Override
    public void put(String bucket, String key, InputStream in, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 对象写入失败: " + key, e);
        }
    }

    /**
     * 打开对象内容流。
     *
     * @param bucket 桶名
     * @param key    对象名
     * @return 内容流
     */
    @Override
    public InputStream get(String bucket, String key) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 对象读取失败: " + key, e);
        }
    }

    /**
     * 对象是否存在。
     *
     * @param bucket 桶名
     * @param key    对象名
     * @return 存在为 true
     */
    @Override
    public boolean exists(String bucket, String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 桶不存在时创建 */
    private void ensureBucket(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 桶初始化失败: " + bucket, e);
        }
    }
}
