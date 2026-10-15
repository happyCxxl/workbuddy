package com.workbuddy.common.enums.input;

/**
 * 文件校验失败原因。
 * 落库：wb_submit_log.fail_reason 存枚举名（name()），校验失败不建结果、不建任务。
 *
 * @author cxxl
 */
public enum FileValidationFailReason {

    /** 文件不存在 / 不可读（文件档案查不到或取流失败） */
    FILE_NOT_FOUND,

    /** 真实格式不在白名单（Tika 魔数识别，改后缀文件在此拦截） */
    FORMAT_NOT_ALLOWED,

    /** 超过大小上限 */
    FILE_TOO_LARGE,

    /** 文件损坏（Tika 探测解析失败/结构异常） */
    FILE_CORRUPTED,

    /** 文件加密（PDF 加密 / Office 密码保护） */
    FILE_ENCRYPTED,

    /** 元数据与实际不符 */
    METADATA_MISMATCH,

    /** 图片文件（JPG/PNG/TIFF/BMP）可识别但 OCR 未开放（预留未开放） */
    IMAGE_OCR_RESERVED
}
