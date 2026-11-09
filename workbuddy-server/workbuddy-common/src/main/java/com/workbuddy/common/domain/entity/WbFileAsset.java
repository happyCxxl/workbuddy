package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseCreateInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 解析资产档案（wb_file_asset）：页图 / 裁剪图 / 嵌入图，按知识库文件归属，随解析产物留存。
 * 对象本体按内容寻址（sha256）写在对象存储；本表只登记归属与位置，供枚举、统计与清理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wb_file_asset")
public class WbFileAsset extends BaseCreateInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 资产 ID（asset-&lt;sha256&gt;；同内容跨任务可重复） */
    private String assetId;

    /** 归属知识库文件（一次解析） */
    private Long knowledgeFileId;

    /** 资产类型（PAGE_IMAGE / IMAGE_CROP / EMBEDDED_IMAGE） */
    private String kind;

    /** 文档页（可空） */
    private Integer page;

    /** 区域（JSON 文本，可空） */
    private String bbox;

    /** 像素宽（可空） */
    private Integer width;

    /** 像素高（可空） */
    private Integer height;

    /** MIME 类型 */
    private String mimeType;

    /** 字节数 */
    private Long fileSize;

    /** 内容指纹（sha256） */
    private String sha256;

    /** 存储类型（StorageType 枚举码） */
    private String storageType;

    /** 数据源实例 ID（读取按它定位后端） */
    private Long storageSourceId;

    /** 存储桶名 */
    private String bucket;

    /** 对象键 */
    private String objectKey;

    /** 公开读取地址（可空） */
    private String url;
}
