package com.workbuddy.common.domain.storage;

import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;

/**
 * 对象位置：定位一个对象所需的全部信息（数据源实例 + 存储类型 + 桶名 + 对象键）。
 *
 * <p>散在各表的引用都按同一形态落库：源文件（wb_file_object）、阶段产物、切片集合、
 * 向量集合（artifact_id 存 sha256）、头像（wb_user.avatar 存相对 key）。读取按
 * {@link #sourceId()} 定位后端，与当前启用的数据源无关；{@link #storageType()} 随行保留，
 * 用于展示与降级。
 *
 * <p>四段缺一不可：数据源为空、类型空或未知、桶名空、对象键空都按「存储后端未配置」处理，
 * 不回落成当前启用的数据源。
 *
 * @param sourceId    数据源实例 ID（wb_storage_source.id）
 * @param storageType 存储类型
 * @param bucket      桶名（local 下为存储根下的一级子目录）
 * @param objectKey   对象键（fileId / sha256 / 头像相对 key）
 */
public record ObjectRef(Long sourceId, StorageType storageType, String bucket, String objectKey) {

    /**
     * 源文件位置（wb_file_object 档案行）。
     *
     * @param row 文件档案行
     * @return 对象位置
     */
    public static ObjectRef ofFile(WbFileObject row) {
        return of(row.getStorageSourceId(), row.getStorageType(), row.getBucket(), row.getObjectKey());
    }

    /**
     * 阶段产物位置（wb_knowledge_pipeline_product 行）。
     *
     * @param row 产物行
     * @return 对象位置
     */
    public static ObjectRef ofProduct(WbPipelineProduct row) {
        return of(row.getStorageSourceId(), row.getStorageType(), row.getBucket(), row.getArtifactId());
    }

    /**
     * 切片集合位置（wb_knowledge_chunk_set 行）。
     *
     * @param row 切片集合行
     * @return 对象位置
     */
    public static ObjectRef ofChunkSet(WbChunkSet row) {
        return of(row.getStorageSourceId(), row.getStorageType(), row.getBucket(), row.getArtifactId());
    }

    /**
     * 向量集合位置（wb_knowledge_embedding_set 行）。
     *
     * @param row 向量集合行
     * @return 对象位置
     */
    public static ObjectRef ofEmbeddingSet(WbEmbeddingSet row) {
        return of(row.getStorageSourceId(), row.getStorageType(), row.getBucket(), row.getArtifactId());
    }

    /**
     * 按四段字段组装对象位置。
     *
     * @param sourceId    数据源实例 ID（可空）
     * @param storageType 存储类型码值（可空）
     * @param bucket      桶名
     * @param objectKey   对象键
     * @return 对象位置
     * @throws WorkbuddyException 数据源为空、类型空或未知、桶名或对象键为空（40455）
     */
    public static ObjectRef of(Long sourceId, String storageType, String bucket, String objectKey) {
        StorageType type = StorageType.of(storageType);
        ThrowUtil.throwIf(NullUtil.isNull(sourceId) || NullUtil.isNull(type)
                || NullUtil.isNull(bucket) || bucket.isBlank()
                || NullUtil.isNull(objectKey) || objectKey.isBlank(),
                ErrorCode.STORAGE_BACKEND_UNCONFIGURED,
                "对象位置不完整: sourceId=" + sourceId + ", storageType=" + storageType
                        + ", bucket=" + bucket + ", objectKey=" + objectKey);
        return new ObjectRef(sourceId, type, bucket, objectKey);
    }

    /**
     * 存储内完整路径（日志与报错用）。
     *
     * @return 存储类型码值、桶名与对象键拼成的路径
     */
    public String path() {
        return storageType.getCode() + "/" + bucket + "/" + objectKey;
    }
}
