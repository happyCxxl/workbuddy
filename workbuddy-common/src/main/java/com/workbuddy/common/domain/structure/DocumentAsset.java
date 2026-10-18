package com.workbuddy.common.domain.structure;

import lombok.Data;

/**
 * 二进制资源引用：只存引用不复制（原图指 file-manager、截图指产物存储）。
 */
@Data
public class DocumentAsset {

    /** 关联元素 ID */
    private String elementId;

    /** 原文件引用（file-manager 文件 ID） */
    private String fileId;

    /** 产物存储引用（截图等） */
    private String assetPath;

    /** 区域信息 */
    private String region;
}
