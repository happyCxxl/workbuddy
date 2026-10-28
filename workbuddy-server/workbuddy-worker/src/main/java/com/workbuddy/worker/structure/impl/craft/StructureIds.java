package com.workbuddy.worker.structure.impl.craft;
import com.workbuddy.common.enums.structure.UnifiedElementType;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 统一元素 ID 生成：来源前缀 + 内容哈希（sha256 前 12 位）。
 * 单文档内唯一；跨处理不承诺稳定（跨处理对照用 provenance + 内容哈希）。
 */
final class StructureIds {

    private StructureIds() {
    }

    /** 前缀：n- 文本 / t- 表格 / tc- 单元格 / i- 图片 / h- 页眉 / s- 章节 */
    static String prefixOf(UnifiedElementType type) {
        return switch (type) {
            case TABLE -> "t";
            case TABLE_CELL -> "tc";
            case IMAGE -> "i";
            case HEADER, FOOTER -> "h";
            case SECTION -> "s";
            default -> "n";
        };
    }

    static String of(UnifiedElementType type, String seed) {
        return prefixOf(type) + "-" + sha12(seed);
    }

    static String sha12(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
