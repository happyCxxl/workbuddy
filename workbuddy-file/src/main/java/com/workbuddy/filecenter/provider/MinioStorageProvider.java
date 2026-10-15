package com.workbuddy.filecenter.provider;

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
 *
 * @author cxxl
 */
@Slf4j
public class MinioStorageProvider implements StorageProvider {

    private final StorageSourceDef def;

    private final MinioClient minioClient;

    public MinioStorageProvider(StorageSourceDef def) {
        this.def = def;
        this.minioClient = MinioClient.builder()
                .endpoint(def.param(StorageSourceDef.KEY_ENDPOINT))
                .credentials(def.param(StorageSourceDef.KEY_ACCESS_KEY),
                        def.param(StorageSourceDef.KEY_SECRET_KEY))
                .build();
    }

    @Override
    public StorageType type() {
        return StorageType.MINIO;
    }

    @Override
    public void initialize() {
        ensureBucket(def.fileBucket());
        ensureBucket(def.artifactBucket());
        log.info("MinIO 桶就绪: {}/{}（数据源 {}, 端点 {}）", def.fileBucket(), def.artifactBucket(),
                def.name(), def.param(StorageSourceDef.KEY_ENDPOINT));
    }

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
