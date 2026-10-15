package com.workbuddy.filecenter.provider;

import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.utils.NullUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储数据源定义：运行时持有的一份连接参数（kb_storage_source.config_json 解析后的形态）。
 *
 * <p>参数按存储类型定义，键与值都是字符串，键名与页面提交的一致：
 * MinIO 取 {@link #KEY_ENDPOINT}、{@link #KEY_ACCESS_KEY}、{@link #KEY_SECRET_KEY}、
 * {@link #KEY_FILE_BUCKET}、{@link #KEY_ARTIFACT_BUCKET}；
 * 本地磁盘取 {@link #KEY_ROOT_DIR}、{@link #KEY_FILE_DIR}、{@link #KEY_ARTIFACT_DIR}。
 *
 * @param id     数据源实例 ID（kb_storage_source.id）
 * @param name   数据源名称（日志与提示文案用）
 * @param type   存储类型
 * @param params 连接参数（按键取值，缺失返回 null）
 * @author cxxl
 */
public record StorageSourceDef(Long id, String name, StorageType type, Map<String, String> params) {

    /** MinIO 服务端点参数名 */
    public static final String KEY_ENDPOINT = "endpoint";

    /** MinIO 访问密钥参数名 */
    public static final String KEY_ACCESS_KEY = "accessKey";

    /** MinIO 私有密钥参数名 */
    public static final String KEY_SECRET_KEY = "secretKey";

    /** MinIO 文件对象桶名参数名 */
    public static final String KEY_FILE_BUCKET = "fileBucket";

    /** MinIO 内容寻址对象桶名参数名 */
    public static final String KEY_ARTIFACT_BUCKET = "artifactBucket";

    /** 本地磁盘存储根参数名 */
    public static final String KEY_ROOT_DIR = "rootDir";

    /** 本地磁盘文件对象目录名参数名 */
    public static final String KEY_FILE_DIR = "fileDir";

    /** 本地磁盘内容寻址对象目录名参数名 */
    public static final String KEY_ARTIFACT_DIR = "artifactDir";

    /** MinIO 必填参数 */
    private static final List<String> MINIO_REQUIRED =
            List.of(KEY_ENDPOINT, KEY_ACCESS_KEY, KEY_SECRET_KEY, KEY_FILE_BUCKET, KEY_ARTIFACT_BUCKET);

    /** 本地磁盘必填参数 */
    private static final List<String> LOCAL_REQUIRED = List.of(KEY_ROOT_DIR, KEY_FILE_DIR, KEY_ARTIFACT_DIR);

    /**
     * 取参数值。
     *
     * @param key 参数名
     * @return 参数值；参数缺失返回 null
     */
    public String param(String key) {
        return NullUtil.isNull(params) ? null : params.get(key);
    }

    /** 文件对象桶名（local 下为存储根下的一级子目录名） */
    public String fileBucket() {
        return param(fileBucketKey(type));
    }

    /** 内容寻址对象桶名（local 下为存储根下的一级子目录名） */
    public String artifactBucket() {
        return param(artifactBucketKey(type));
    }

    /**
     * 该类型的文件对象桶名参数名。
     *
     * @param type 存储类型
     * @return 参数名
     */
    public static String fileBucketKey(StorageType type) {
        return switch (type) {
            case MINIO -> KEY_FILE_BUCKET;
            case LOCAL -> KEY_FILE_DIR;
        };
    }

    /**
     * 该类型的内容寻址对象桶名参数名。
     *
     * @param type 存储类型
     * @return 参数名
     */
    public static String artifactBucketKey(StorageType type) {
        return switch (type) {
            case MINIO -> KEY_ARTIFACT_BUCKET;
            case LOCAL -> KEY_ARTIFACT_DIR;
        };
    }

    /**
     * 该类型的必填参数名（新增存储类型时这里补一行）。
     *
     * @param type 存储类型
     * @return 必填参数名清单
     */
    public static List<String> requiredKeys(StorageType type) {
        return switch (type) {
            case MINIO -> MINIO_REQUIRED;
            case LOCAL -> LOCAL_REQUIRED;
        };
    }

    /**
     * 缺失的必填参数名。
     *
     * @param def 数据源定义
     * @return 缺失（为空或空白）的必填参数名清单
     */
    public static List<String> missingKeys(StorageSourceDef def) {
        return missingKeys(def.type(), def.params());
    }

    /**
     * 缺失的必填参数名。
     *
     * @param type   存储类型
     * @param params 连接参数
     * @return 缺失（为空或空白）的必填参数名清单
     */
    public static List<String> missingKeys(StorageType type, Map<String, String> params) {
        List<String> missing = new ArrayList<>();
        for (String key : requiredKeys(type)) {
            if (isBlank(NullUtil.isNull(params) ? null : params.get(key))) {
                missing.add(key);
            }
        }
        return missing;
    }

    /**
     * 只保留值非空白的参数，键与值两侧空白去掉。
     *
     * @param params 连接参数（可空）
     * @return 规整后的参数
     */
    public static Map<String, String> normalize(Map<String, String> params) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (NullUtil.isNull(params)) {
            return normalized;
        }
        params.forEach((key, value) -> {
            if (!isBlank(key) && !isBlank(value)) {
                normalized.put(key.trim(), value.trim());
            }
        });
        return normalized;
    }

    /** 是否为空或全空白 */
    public static boolean isBlank(String value) {
        return NullUtil.isNull(value) || value.isBlank();
    }
}
