package com.workbuddy.common.enums.input;

import cn.hutool.core.util.StrUtil;

/**
 * 文件校验失败原因。
 * 落库：wb_submit_log.fail_reason 存枚举名（name()），校验失败不建结果、不建任务。
 * 展示：中文名随枚举维护，{@link #labelOf(String)} 把库里的码值翻译回中文（未知码值原样返回）。
 */
public enum FileValidationFailReason {

    /** 文件不存在 / 不可读（文件档案查不到或取流失败） */
    FILE_NOT_FOUND("文件不存在或不可读"),

    /** 真实格式不在白名单（Tika 魔数识别，改后缀文件在此拦截） */
    FORMAT_NOT_ALLOWED("格式不在允许范围"),

    /** 超过大小上限 */
    FILE_TOO_LARGE("文件超过大小上限"),

    /** 文件损坏（Tika 探测解析失败/结构异常） */
    FILE_CORRUPTED("文件已损坏"),

    /** 文件加密（PDF 加密 / Office 密码保护） */
    FILE_ENCRYPTED("文件已加密"),

    /** 元数据与实际不符 */
    METADATA_MISMATCH("元数据与实际不符"),

    /** 图片文件（JPG/PNG/TIFF/BMP）可识别但 OCR 未开放（预留未开放） */
    IMAGE_OCR_RESERVED("图片类型暂不支持");

    /** 展示用中文名 */
    private final String label;

    FileValidationFailReason(String label) {
        this.label = label;
    }

    /**
     * 展示用中文名。
     *
     * @return 中文名
     */
    public String label() {
        return label;
    }

    /**
     * 按库中码值取中文名：忽略大小写与首尾空白，未知码值原样返回。
     *
     * @param reason 失败原因码值（库表 fail_reason）
     * @return 中文名；空值返回 null，未识别码值返回原码值
     */
    public static String labelOf(String reason) {
        if (StrUtil.isBlank(reason)) {
            return null;
        }
        for (FileValidationFailReason item : values()) {
            if (item.name().equalsIgnoreCase(reason.trim())) {
                return item.label;
            }
        }
        return reason;
    }
}
