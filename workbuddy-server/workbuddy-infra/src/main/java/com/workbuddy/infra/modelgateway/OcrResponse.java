package com.workbuddy.infra.modelgateway;

import java.util.Map;

/**
 * OCR 网关响应（data 段直译）：text 为模型原文，fields 为按调用方 schema 解析出的结构化字段
 * （bbox 为输入图像像素坐标），pages 为识别页数，meta 为网关元信息。
 * 映射业务实体（如 StructuredOcrResult）归调用方，本类只承载网关返回形态。
 */
public record OcrResponse(String text, Map<String, Object> fields, int pages, Map<String, Object> meta) {

    /**
     * 从网关 data 段解析：text/pages 缺失给空值，fields/meta 缺失给 null。
     *
     * @param data 网关 data 段
     * @return 直译记录
     */
    public static OcrResponse from(Map<String, Object> data) {
        Object rawText = data.get("text");
        Object rawPages = data.get("pages");
        return new OcrResponse(rawText instanceof String text ? text : "",
                GatewayDataSupport.mapOf(data, "fields"),
                rawPages instanceof Number pages ? pages.intValue() : 0,
                GatewayDataSupport.mapOf(data, "meta"));
    }
}
