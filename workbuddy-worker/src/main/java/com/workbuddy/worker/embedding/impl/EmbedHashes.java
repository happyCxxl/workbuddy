package com.workbuddy.worker.embedding.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 输入文本指纹：sha256 hex（复用键之一；与产物存储 sha256 寻址同一算法）。
 * 不用 hutool-crypto（worker 只依赖 hutool-core），与 LocalFileArtifactRepository/StructureIds 同风格。
 *
 * @author cxxl
 */
final class EmbedHashes {

    private EmbedHashes() {
    }

    /** 文本 → sha256 hex（小写） */
    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            // JDK 必有 SHA-256；防御性兜底，不吞异常语义
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
