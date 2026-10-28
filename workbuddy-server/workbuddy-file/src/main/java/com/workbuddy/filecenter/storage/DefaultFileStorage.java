package com.workbuddy.filecenter.storage;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.db.WbFileObjectDbService;
import com.workbuddy.infra.storage.StorageProvider;
import com.workbuddy.filecenter.provider.StorageRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文件存储实现：fileId 寻址（对象写当前启用的数据源，元数据写 wb_file_object 档案表）；
 * 内容寻址与指定 key 的对象读写继承自 {@link AbstractFileStorage}。
 *
 * <p>类名与后端无关：注入的 StorageRouter 按数据源定义决定字节落在 MinIO 还是本地磁盘。
 */
@Slf4j
@Service
public class DefaultFileStorage extends AbstractFileStorage {

    /**
     * 构造默认存储实现。
     *
     * @param router              数据源路由
     * @param fileObjectDbService 文件档案访问
     */
    public DefaultFileStorage(StorageRouter router, WbFileObjectDbService fileObjectDbService) {
        super(router, fileObjectDbService);
    }

    /**
     * 写入文件对象：fileId 寻址，每次写入新对象并登记档案，同时记录内容指纹。
     *
     * @param file 上传的文件
     * @return 新建的 fileId
     */
    @Override
    public String putFile(MultipartFile file) {
        String fileId = IdWorker.getIdStr();
        StorageProvider provider = router.writer();
        Long sourceId = router.currentSourceId();
        String bucket = router.currentFileBucket();
        String sha256;
        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            sha256 = putWithDigest(bucket, fileId, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            log.warn("文件上传失败, fileName={}", file.getOriginalFilename(), e);
            throw new WorkbuddyException(ErrorCode.SYSTEM_ERROR, "文件上传失败: " + file.getOriginalFilename());
        }

        WbFileObject object = new WbFileObject();
        object.setFileId(fileId);
        object.setFileName(file.getOriginalFilename());
        object.setMimeType(file.getContentType());
        object.setFileSize(file.getSize());
        object.setSha256(sha256);
        object.setStorageType(provider.type().getCode());
        object.setStorageSourceId(sourceId);
        object.setBucket(bucket);
        object.setObjectKey(fileId);
        fileObjectDbService.save(object);
        return fileId;
    }

    /**
     * 按 fileId 打开文件流，读取后端由档案里的数据源决定。
     *
     * @param fileId 文件 ID
     * @return 内容流
     */
    @Override
    public InputStream open(String fileId) {
        return openRef(ObjectRef.ofFile(requireObject(fileId)));
    }

    /**
     * 读文件元数据。
     *
     * @param fileId 文件 ID
     * @return 元数据（文件名、大小、类型、指纹、创建时间）
     */
    @Override
    public FileMetadata metadata(String fileId) {
        WbFileObject object = requireObject(fileId);
        FileMetadata metadata = new FileMetadata();
        metadata.setFileId(object.getFileId());
        metadata.setFileName(object.getFileName());
        metadata.setFileSize(object.getFileSize());
        metadata.setMimeType(object.getMimeType());
        metadata.setSha256(object.getSha256());
        metadata.setCreateTime(object.getCreateTime());
        return metadata;
    }

    /**
     * 文件档案是否存在。
     *
     * @param fileId 文件 ID
     * @return 存在为 true
     */
    @Override
    public boolean exists(String fileId) {
        return NullUtil.isNotNull(fileObjectDbService.getByFileId(fileId));
    }
}
