package com.workbuddy.filecenter.service;

import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.storage.StorageRef;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Collection;
import java.util.Map;

/**
 * 文件存储：对象写入与读取（读写的后端由 StorageRouter 按记录里的数据源 ID 选）。
 *
 * <p>寻址方式三档：fileId 寻址（上传文件，元数据入档案库）、sha256 内容寻址（阶段产物、
 * 切片集合、向量集合，同内容幂等复用）、指定 key（头像，键写在业务表里）。
 *
 * @author cxxl
 */
public interface FileStorage {

    // —— fileId 寻址：每次写入新对象，元数据入档案库 ——

    /** 写入文件对象，返回 fileId */
    String putFile(MultipartFile file);

    /** 按档案里的数据源与桶打开文件流 */
    InputStream open(String fileId);

    /** 读取文件元数据 */
    FileMetadata metadata(String fileId);

    /** 文件档案是否存在 */
    boolean exists(String fileId);

    /** 文件档案的存储口径（类型码值 + 数据源实例 ID） */
    StorageRef refOf(String fileId);

    /** 批量取文件档案的存储口径（按 fileId 索引；没有档案的不在结果里） */
    Map<String, StorageRef> refsOf(Collection<String> fileIds);

    // —— sha256 内容寻址与指定 key：阶段产物、切片集合、向量集合、头像 ——

    /** 写入对象（sha256 内容寻址），返回对象位置 */
    ObjectRef putObject(byte[] content);

    /** 按指定 key 写入对象，返回对象位置 */
    ObjectRef putRaw(String key, InputStream in, long size, String contentType);

    /** 按对象位置读取 */
    byte[] getObject(ObjectRef ref);

    /** 按对象位置判断存在 */
    boolean objectExists(ObjectRef ref);
}
