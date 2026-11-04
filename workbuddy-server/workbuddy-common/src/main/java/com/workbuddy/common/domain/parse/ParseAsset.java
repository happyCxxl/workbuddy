package com.workbuddy.common.domain.parse;

import com.workbuddy.common.enums.parse.ParseAssetKind;
import lombok.Data;

/**
 * 解析资产：图片字节在对象存储中的位置与展示/OCR 所需的元数据。
 *
 * <p>{@code ParseElement.assetRef} 指向本对象的 {@code assetId}；资产按内容寻址，
 * 相同字节只对应一个 {@code ParseAsset}。对象位置四段平铺，避免嵌套记录在产物 JSON 反序列化时丢类型。
 */
@Data
public class ParseAsset {

    /** 资产 ID（解析产物内唯一，当前为内容 sha256 派生） */
    private String assetId;

    /** 资产类型 */
    private ParseAssetKind kind;

    /** 数据源实例 ID */
    private Long storageSourceId;

    /** 存储类型码值 */
    private String storageType;

    /** 桶名 */
    private String bucket;

    /** 对象键 */
    private String objectKey;

    /** 图片 MIME 类型 */
    private String mimeType;

    /** 字节数 */
    private Long size;

    /** 内容 sha256（与对象键一致） */
    private String sha256;

    /** 图片所在文档页（Office 无物理页时为 null） */
    private Integer page;

    /** 图片区域（页图取整页；嵌入图片可空） */
    private BBox bbox;

    /** 图片宽度（像素；未知为 null） */
    private Integer width;

    /** 图片高度（像素；未知为 null） */
    private Integer height;
}
