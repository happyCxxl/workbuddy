package com.workbuddy.filecenter.storage;

import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.db.WbFileObjectDbService;
import com.workbuddy.infra.storage.StorageProvider;
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
 */
public abstract class AbstractFileStorage implements FileStorage {

    protected final StorageRouter router;

    protected final WbFileObjectDbService fileObjectDbService;

    /**
     * 构造存储实现。
     *
     * @param router              数据源路由
     * @param fileObjectDbService 文件档案访问
     */
    protected AbstractFileStorage(StorageRouter router, WbFileObjectDbService fileObjectDbService) {
        this.router = router;
        this.fileObjectDbService = fileObjectDbService;
    }

    /**
     * 内容寻址写入：相同内容只写一次，重复写入直接复用已有对象。
     *
     * @param content 对象字节
     * @return 对象位置（数据源、存储类型、桶名、sha256）
     */
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

    /**
     * 按调用方给定的键写入对象：键由业务表持有，用于头像这类需要稳定寻址的对象。
     *
     * @param key         对象键
     * @param in          对象内容流
     * @param size        内容字节数
     * @param contentType 内容类型
     * @return 对象位置（数据源、存储类型、桶名、键）
     */
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

    /**
     * 读取整个对象到内存。
     *
     * @param ref 对象位置
     * @return 对象字节
     * @throws KnowledgeException 对象缺失（40454）或读取故障（40500）
     */
    @Override
    public byte[] getObject(ObjectRef ref) {
        try (InputStream in = openRef(ref)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "对象读取失败: " + ref.path());
        }
    }

    /**
     * 对象是否存在于它所属的后端。
     *
     * @param ref 对象位置
     * @return 存在为 true
     */
    @Override
    public boolean objectExists(ObjectRef ref) {
        return router.of(ref).exists(ref.bucket(), ref.objectKey());
    }

    /**
     * 取文件档案的存储口径。
     *
     * @param fileId 文件 ID
     * @return 存储口径（类型码值与数据源实例 ID）
     * @throws KnowledgeException 档案不存在（40402）
     */
    @Override
    public StorageRef refOf(String fileId) {
        return StorageRef.of(requireObject(fileId));
    }

    /**
     * 批量取文件档案的存储口径，按 fileId 索引；没有档案的不在结果里。
     *
     * @param fileIds 文件 ID 集合
     * @return fileId 到存储口径的映射
     */
    @Override
    public Map<String, StorageRef> refsOf(Collection<String> fileIds) {
        Map<String, StorageRef> refs = new HashMap<>();
        for (WbFileObject row : fileObjectDbService.listByFileIds(fileIds)) {
            refs.put(row.getFileId(), StorageRef.of(row));
        }
        return refs;
    }

    /**
     * 摘要写入：边写边算 sha256，写入当前启用的数据源后返回摘要。
     *
     * @param bucket      桶名
     * @param key         对象键
     * @param in          对象内容流
     * @param size        内容字节数
     * @param contentType 内容类型
     * @return 内容的 sha256 十六进制
     */
    protected String putWithDigest(String bucket, String key, InputStream in, long size, String contentType) {
        try {
            DigestInputStream digest = new DigestInputStream(in, MessageDigest.getInstance("SHA-256"));
            router.writer().put(bucket, key, digest, size, contentType);
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "对象写入失败: " + key);
        }
    }

    /**
     * 取文件档案。
     *
     * @param fileId 文件 ID
     * @return 档案行
     * @throws KnowledgeException 档案不存在（40402）
     */
    protected WbFileObject requireObject(String fileId) {
        WbFileObject object = fileObjectDbService.getByFileId(fileId);
        ThrowUtil.throwIf(NullUtil.isNull(object), ErrorCode.FILE_NOT_FOUND);
        return object;
    }

    /**
     * 按对象位置打开流。
     *
     * @param ref 对象位置
     * @return 对象内容流
     * @throws KnowledgeException 对象缺失（40454）或读取故障（40500）
     */
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

    /**
     * 计算字节内容的 sha256 指纹。
     *
     * @param content 对象字节
     * @return 摘要的十六进制串
     */
    protected String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "对象指纹计算失败");
        }
    }
}
