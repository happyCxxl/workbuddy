package com.workbuddy.biz.service;

/**
 * 解析资产读取结果：图片字节与 MIME（文件接口直接回写响应体，不包统一 JSON）。
 *
 * @param mimeType 图片 MIME
 * @param content  图片字节
 */
public record ParseAssetContent(String mimeType, byte[] content) {
}
