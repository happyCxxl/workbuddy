package com.workbuddy.common.enums.structure;

/**
 * 页面级标记（字典）：识别环节（组装）写入、处置环节（预处理）读取。
 * 只增不删；仅 PDF（有页概念），Word/Excel 页级不适用。
 */
public enum PageMark {

    /** 重复页（组装环节识别；本页为除首份外的重复页） */
    REPEATED_PAGE,

    /** 噪声页（空白页/纯图片占位页/乱码率超阈值页） */
    NOISE_PAGE
}
