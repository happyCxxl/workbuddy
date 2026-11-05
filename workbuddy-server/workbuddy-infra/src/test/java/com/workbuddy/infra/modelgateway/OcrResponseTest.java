package com.workbuddy.infra.modelgateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OCR 网关响应直译解析：缺失给空值，正常数据按类型提取。
 */
class OcrResponseTest {

    /**
     * 全缺省：text 空串、fields/meta null、pages 0。
     */
    @Test
    void fromShouldDefaultWhenDataEmpty() {
        OcrResponse response = OcrResponse.from(Map.of());
        assertEquals("", response.text());
        assertNull(response.fields());
        assertEquals(0, response.pages());
        assertNull(response.meta());
    }

    /**
     * 正常数据：text/pages/fields/meta 各自提取。
     */
    @Test
    void fromShouldExtractAllFields() {
        OcrResponse response = OcrResponse.from(Map.of(
                "text", "原文",
                "pages", 2,
                "fields", Map.of("text_lines", List.of(Map.of("text", "行"))),
                "meta", Map.of("model_key", "ocr")));
        assertEquals("原文", response.text());
        assertEquals(2, response.pages());
        assertEquals(1, ((List<?>) response.fields().get("text_lines")).size());
        assertEquals("ocr", response.meta().get("model_key"));
    }

    /**
     * pages 非数字回落 0。
     */
    @Test
    void fromShouldDefaultPagesWhenNotNumber() {
        OcrResponse response = OcrResponse.from(Map.of("pages", "x"));
        assertEquals(0, response.pages());
    }
}
