package com.workbuddy.common.domain.parse;

import com.workbuddy.common.enums.parse.ParseSourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析来源单测。
 *
 * <p>守护 {@code nativeSource}：路名固定为 native、提供方原样、带候选顺序标记；
 * 路名写错会让组装环节把原生解析当成 OCR 路。
 */
class ParseSourceTest {

    /**
     * 原生路：source= native、provider 原样、candidateOrder=true。
     *
     * <p>守护 {@code nativeSource}。
     */
    @Test
    void nativeSourceShouldCarryFixedRoute() {
        ParseSource source = ParseSource.nativeSource("pdfbox-3.0.4");

        assertEquals(ParseSourceType.NATIVE.value(), source.getSource());
        assertEquals("pdfbox-3.0.4", source.getProvider());
        assertTrue(source.getCandidateOrder());
        assertTrue(source.getElements().isEmpty());
    }
}
