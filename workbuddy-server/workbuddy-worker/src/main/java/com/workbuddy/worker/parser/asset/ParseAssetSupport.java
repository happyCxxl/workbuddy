package com.workbuddy.worker.parser.asset;

import com.workbuddy.common.utils.NullUtil;

import java.util.Locale;

/**
 * 资产元数据辅助：图片 MIME 归一与扩展名兜底。
 */
public final class ParseAssetSupport {

    private ParseAssetSupport() {
    }

    /**
     * 图片 MIME：优先用声明值，缺失时按扩展名兜底。
     *
     * @param declared 声明的 MIME（可空）
     * @param extension 文件扩展名（可空，不含点）
     * @return 图片 MIME；无法判断时为 application/octet-stream
     */
    public static String imageMime(String declared, String extension) {
        if (NullUtil.isNotNull(declared) && !declared.isBlank()) {
            return declared;
        }
        String ext = NullUtil.isNull(extension) ? "" : extension.trim().toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp", "dib" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            case "emf" -> "image/emf";
            case "wmf" -> "image/wmf";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }
}
