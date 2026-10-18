package com.workbuddy.filecenter.storage;

import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.db.WbFileObjectDbService;
import com.workbuddy.filecenter.provider.StorageProvider;
import com.workbuddy.filecenter.provider.StorageRouter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 文件存储模板基类：内容寻址对象与文件档案的公共实现。
 *
 * <p>写入走当前启用的数据源（{@link StorageRouter#writer()}），桶名取该数据源的参数；
 * 读取按记录里的数据源 ID 选后端，桶名取自记录，不由定义回填。
 *
 * @author cxxl
 */
public abstract class AbstractFileStorage implements FileStorage {

    protected final StorageRouter router;

    protected final WbFileObjectDbService fileObjectDbService;

    protected AbstractFileStorage(StorageRouter router, WbFileObjectDbService fileObjectDbService) {
        this.router = router;
        this.fileObjectDbService = fileObjectDbService;
    }

    @Override
    public ObjectRef putObject(byte[] content) {
        String sha256 = sha256Hex(content);
        StorageProvider provider = router.writer();
        Long sourceId = router.currentSourceId();
        String bucket = router.currentArtifactBucket();
        try {
            if (!provider.exists(bucket, sha256)) {
                provider.put(bucket, sha256, new ByteArrayInputStream(content), content.length,
                        "application/octet-stream");
            }
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "对象写入失败: " + sha256);
        }
        return new ObjectRef(sourceId, provider.type(), bucket, sha256);
    }

    @Override
    public ObjectRef putRaw(String key, InputStream in, long size, String contentType) {
        StorageProvider provider = router.writer();
        Long sourceId = router.currentSourceId();
        String bucket = router.currentFileBucket();
        try {
            provider.put(bucket, key, in, size, contentType);
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "对象写入失败: " + key);
        }
        return new ObjectRef(sourceId, provider.type(), bucket, key);
    }

    @Override
    public byte[] getObject(ObjectRef ref) {
        try (InputStream in = openRef(ref)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "对象读取失败: " + ref.path());
        }
    }

    @Override
    public boolean objectExists(ObjectRef ref) {
        return router.of(ref).exists(ref.bucket(), ref.objectKey());
    }

    @Override
    public StorageRef refOf(String fileId) {
        return StorageRef.of(requireObject(fileId));
    }

    @Override
    public Map<String, StorageRef> refsOf(Collection<String> fileIds) {
        Map<String, StorageRef> refs = new HashMap<>();
        for (WbFileObject row : fileObjectDbService.listByFileIds(fileIds)) {
            refs.put(row.getFileId(), StorageRef.of(row));
        }
        return refs;
    }

    /** 摘要写入：流经 sha256 摘要后写入当前启用的数据源，返回摘要十六进制 */
    protected String putWithDigest(String bucket, String key, InputStream in, long size, String contentType) {
        try {
            DigestInputStream digest = new DigestInputStream(in, MessageDigest.getInstance("SHA-256"));
            router.writer().put(bucket, key, digest, size, contentType);
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "对象写入失败: " + key);
        }
    }

    /** 取档案，不存在抛业务错误 */
    protected WbFileObject requireObject(String fileId) {
        WbFileObject object = fileObjectDbService.getByFileId(fileId);
        ThrowUtil.throwIf(NullUtil.isNull(object), ErrorCode.FILE_NOT_FOUND);
        return object;
    }

    /** 按对象位置打开流：对象缺失报 40454，其余读取故障报 40500 */
    protected InputStream openRef(ObjectRef ref) {
        StorageProvider provider = router.of(ref);
        try {
            return provider.get(ref.bucket(), ref.objectKey());
        } catch (Exception e) {
            if (!provider.exists(ref.bucket(), ref.objectKey())) {
                throw new KnowledgeException(ErrorCode.STORAGE_OBJECT_MISSING, "对象不存在: " + ref.path());
            }
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "对象读取失败: " + ref.path());
        }
    }

    /** 字节内容摘要（sha256 十六进制） */
    protected String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "对象指纹计算失败");
        }
    }
}
