package com.workbuddy.common.domain.model.vision;

import com.workbuddy.common.domain.model.chat.OutputSpec;
import lombok.Data;

import java.util.Arrays;

/**
 * 视觉请求：图片字节或图片引用 + 提示词 + 输出约束。
 *
 * <p>字节与引用二选一：提交字节时必须给出 MIME 类型；引用按 URL 交给厂商。
 */
@Data
public class VisionRequest {

    /** 图片字节（与 imageRef 二选一） */
    private byte[] imageBytes;

    /** 图片引用（URL，与 imageBytes 二选一） */
    private String imageRef;

    /** 字节的媒体类型（如 image/png），提交字节时必填 */
    private String mimeType;

    /** 提示词 */
    private String prompt;

    /** 输出约束，空按 TEXT 处理 */
    private OutputSpec output;

    /**
     * 是否提交了图片字节。
     *
     * @return 字节非空为 true
     */
    public boolean hasBytes() {
        return imageBytes != null && imageBytes.length > 0;
    }

    /**
     * 清空字节字段，避免日志或序列化意外带出图片内容。
     */
    public void clearBytes() {
        if (imageBytes != null) {
            Arrays.fill(imageBytes, (byte) 0);
            imageBytes = null;
        }
    }
}
