package com.workbuddy.common.enums.input;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.utils.NullUtil;

import java.util.Map;

/**
 * 文件校验失败原因的中文名（展示口径）。
 *
 * <p>与 {@link AuditActionLabels} 同一套做法：{@link FileValidationFailReason} 是**领域码值**
 * （落库存 `name()`），中文只在读日志时要用，单独放一层映射，
 * 码值语义变更时写入侧不受影响。
 *
 * @author cxxl
 */
public final class FileValidationFailLabels {

    /** 原因 → 中文名；未列出的回落成码值本身 */
    private static final Map<FileValidationFailReason, String> LABELS = Map.of(
            FileValidationFailReason.FILE_NOT_FOUND, "文件不存在或不可读",
            FileValidationFailReason.FORMAT_NOT_ALLOWED, "格式不在允许范围",
            FileValidationFailReason.FILE_TOO_LARGE, "文件超过大小上限",
            FileValidationFailReason.FILE_CORRUPTED, "文件已损坏",
            FileValidationFailReason.FILE_ENCRYPTED, "文件已加密",
            FileValidationFailReason.METADATA_MISMATCH, "元数据与实际不符",
            FileValidationFailReason.IMAGE_OCR_RESERVED, "图片类型暂不支持");

    private FileValidationFailLabels() {
    }

    /**
     * 失败原因中文名。
     *
     * @param reason 失败原因码值（库表 fail_reason）
     * @return 中文名；空值返回 null，未识别码值返回原码值
     */
    public static String label(String reason) {
        if (StrUtil.isBlank(reason)) {
            return null;
        }
        FileValidationFailReason parsed = parse(reason);
        return NullUtil.isNull(parsed) ? reason : LABELS.getOrDefault(parsed, reason);
    }

    private static FileValidationFailReason parse(String reason) {
        for (FileValidationFailReason item : FileValidationFailReason.values()) {
            if (item.name().equalsIgnoreCase(reason.trim())) {
                return item;
            }
        }
        return null;
    }
}
