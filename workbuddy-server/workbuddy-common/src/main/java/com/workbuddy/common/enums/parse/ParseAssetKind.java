package com.workbuddy.common.enums.parse;

/**
 * 解析资产类型：图片元素或 OCR 输入对应的图片本体形态。
 */
public enum ParseAssetKind {

    /** 整页渲染图（扫描页 OCR 与页面预览输入） */
    PAGE_IMAGE,

    /** 页面渲染图按图片 bbox 裁剪出的区域（PDF 嵌入图片） */
    IMAGE_CROP,

    /** 文档内原始嵌入图片（Office PictureData 字节） */
    EMBEDDED_IMAGE
}
