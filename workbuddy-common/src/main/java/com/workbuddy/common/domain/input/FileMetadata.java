package com.workbuddy.common.domain.input;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件元数据：文件服务档案的领域视图。
 */
@Data
public class FileMetadata {

    /** 文件 ID */
    private String fileId;

    /** 文件名 */
    private String fileName;

    /** 扩展名 */
    private String fileExtension;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型 */
    private String mimeType;

    /** 内容指纹（sha256） */
    private String sha256;

    /** 上传时间 */
    private LocalDateTime createTime;
}
