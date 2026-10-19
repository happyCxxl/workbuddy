package com.workbuddy.common.domain.storage;

import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对象位置单测：四段字段的装配、各表工厂的取值口径，以及缺段时的「存储后端未配置」拒绝。
 */
class ObjectRefTest {

    /** 数据源实例 ID */
    private static final Long SOURCE_ID = 7L;

    private WbFileObject fileRow() {
        WbFileObject row = new WbFileObject();
        row.setStorageType(StorageType.MINIO.getCode());
        row.setStorageSourceId(SOURCE_ID);
        row.setBucket("files");
        row.setObjectKey("F-88");
        return row;
    }

    /**
     * 守护 {@code shouldAssembleFromFileRow}：该场景行为改动即失败。
     */
    @Test
    void shouldAssembleFromFileRow() {
        ObjectRef ref = ObjectRef.ofFile(fileRow());

        assertEquals(SOURCE_ID, ref.sourceId());
        assertEquals(StorageType.MINIO, ref.storageType());
        assertEquals("files", ref.bucket());
        assertEquals("F-88", ref.objectKey());
        assertEquals("minio/files/F-88", ref.path());
    }

    /**
     * 守护 {@code productRowShouldUseArtifactIdAsObjectKey}：该场景行为改动即失败。
     */
    @Test
    void productRowShouldUseArtifactIdAsObjectKey() {
        WbPipelineProduct row = new WbPipelineProduct();
        row.setStorageType(StorageType.LOCAL.getCode());
        row.setStorageSourceId(SOURCE_ID);
        row.setBucket("artifacts");
        row.setArtifactId("a".repeat(64));

        ObjectRef ref = ObjectRef.ofProduct(row);

        assertEquals("a".repeat(64), ref.objectKey());
        assertEquals(StorageType.LOCAL, ref.storageType());
    }

    /**
     * 守护 {@code chunkSetAndEmbeddingSetShouldUseArtifactId}：该场景行为改动即失败。
     */
    @Test
    void chunkSetAndEmbeddingSetShouldUseArtifactId() {
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setStorageType(StorageType.MINIO.getCode());
        chunkSet.setStorageSourceId(SOURCE_ID);
        chunkSet.setBucket("artifacts");
        chunkSet.setArtifactId("chunk-artifact");
        WbEmbeddingSet embeddingSet = new WbEmbeddingSet();
        embeddingSet.setStorageType(StorageType.MINIO.getCode());
        embeddingSet.setStorageSourceId(SOURCE_ID);
        embeddingSet.setBucket("artifacts");
        embeddingSet.setArtifactId("embed-artifact");

        assertEquals("chunk-artifact", ObjectRef.ofChunkSet(chunkSet).objectKey());
        assertEquals("embed-artifact", ObjectRef.ofEmbeddingSet(embeddingSet).objectKey());
    }

    /**
     * 守护 {@code missingSourceIdShouldReject40455}：该场景行为改动即失败。
     */
    @Test
    void missingSourceIdShouldReject40455() {
        WbFileObject row = fileRow();
        row.setStorageSourceId(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> ObjectRef.ofFile(row));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        assertTrue(e.getMessage().contains("sourceId=null"), e.getMessage());
    }

    /**
     * 守护 {@code unknownTypeShouldReject40455}：该场景行为改动即失败。
     */
    @Test
    void unknownTypeShouldReject40455() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> ObjectRef.of(SOURCE_ID, "oss", "files", "F-88"));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        assertTrue(e.getMessage().contains("oss"), e.getMessage());
    }

    /**
     * 守护 {@code blankBucketOrObjectKeyShouldReject40455}：该场景行为改动即失败。
     */
    @Test
    void blankBucketOrObjectKeyShouldReject40455() {
        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED,
                assertThrows(KnowledgeException.class,
                        () -> ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "  ", "F-88")).getErrorCode());
        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED,
                assertThrows(KnowledgeException.class,
                        () -> ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "files", null)).getErrorCode());
    }
}
