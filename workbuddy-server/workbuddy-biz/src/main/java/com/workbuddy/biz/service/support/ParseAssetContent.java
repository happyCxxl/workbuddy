package com.workbuddy.biz.service.support;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * 解析资产读取结果：图片字节与 MIME（文件接口直接回写响应体，不包统一 JSON）。
 *
 * @param mimeType 图片 MIME
 * @param content  图片字节
 */
public record ParseAssetContent(String mimeType, byte[] content) {

    /**
     * 解析响应 Content-Type；MIME 非法时回退二进制流。
     *
     * @return 可写入响应的媒体类型
     */
    public MediaType mediaType() {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
