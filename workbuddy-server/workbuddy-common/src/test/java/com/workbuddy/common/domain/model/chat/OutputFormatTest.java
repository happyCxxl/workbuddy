package com.workbuddy.common.domain.model.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 输出格式枚举：三档取值稳定。
 */
class OutputFormatTest {

    /** 取值与顺序固定（{@code shouldKeepThreeFormats}）。 */
    @Test
    void shouldKeepThreeFormats() {
        assertEquals(3, OutputFormat.values().length);
        assertEquals(OutputFormat.TEXT, OutputFormat.valueOf("TEXT"));
        assertEquals(OutputFormat.JSON_OBJECT, OutputFormat.valueOf("JSON_OBJECT"));
        assertEquals(OutputFormat.JSON_SCHEMA, OutputFormat.valueOf("JSON_SCHEMA"));
    }
}
