package com.workbuddy.worker.parser.pdf.extract;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字体 Unicode 映射判据单测：Type0 且缺 ToUnicode 才判映射失败。
 */
class PdfFontMappingTest {

    /**
     * 守护 {@code type0WithoutToUnicodeShouldLackMapping}：该场景行为改动即失败。
     */
    @Test
    void type0WithoutToUnicodeShouldLackMapping() {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.SUBTYPE, COSName.TYPE0);

        assertTrue(PdfFontMapping.lacksUnicodeMapping(font));
    }

    /**
     * 守护 {@code type0WithToUnicodeShouldHaveMapping}：该场景行为改动即失败。
     */
    @Test
    void type0WithToUnicodeShouldHaveMapping() {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.SUBTYPE, COSName.TYPE0);
        font.setItem(COSName.TO_UNICODE, new COSDictionary());

        assertFalse(PdfFontMapping.lacksUnicodeMapping(font));
    }

    /**
     * 守护 {@code simpleFontOrMissingDictionaryShouldHaveMapping}：该场景行为改动即失败。
     */
    @Test
    void simpleFontOrMissingDictionaryShouldHaveMapping() {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);

        assertFalse(PdfFontMapping.lacksUnicodeMapping(font));
        assertFalse(PdfFontMapping.lacksUnicodeMapping((COSDictionary) null));
    }
}
