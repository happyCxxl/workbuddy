package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseCreateInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 文件档案：上传文件的元数据记录（append-only）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wb_file_object")
public class WbFileObject extends BaseCreateInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 文件 ID（雪花字符串，同时是 MinIO 对象键） */
    private String fileId;

    /** 文件名 */
    private String fileName;

    /** MIME 类型 */
    private String mimeType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 内容指纹（sha256） */
    private String sha256;

    /** 存储类型（StorageType 枚举码：minio / local；冗余留作展示与降级） */
    private String storageType;

    /** 数据源实例 ID（wb_storage_source.id）：读取按它定位后端 */
    private Long storageSourceId;

    /** 存储桶名（local 下为一级子目录） */
    private String bucket;

    /** 对象键 */
    private String objectKey;
}
