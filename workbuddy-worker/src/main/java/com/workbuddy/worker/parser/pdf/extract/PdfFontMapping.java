package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.common.utils.NullUtil;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * 字体 Unicode 映射判据：Type0（CID）字体且 COS 字典无 ToUnicode 映射时，
 * 字符码点无法按 Unicode 还原，提取出的文本可能是错位码点（`ÿþ¤` 这类可打印乱码）。
 *
 * @author cxxl
 */
public final class PdfFontMapping {

    private PdfFontMapping() {
    }

    /** 字体是否缺 Unicode 映射 */
    public static boolean lacksUnicodeMapping(PDFont font) {
        return NullUtil.isNotNull(font) && lacksUnicodeMapping(font.getCOSObject());
    }

    /** 字典口径：Type0 子类型且无 ToUnicode 项 */
    public static boolean lacksUnicodeMapping(COSDictionary fontDictionary) {
        if (NullUtil.isNull(fontDictionary)) {
            return false;
        }
        return COSName.TYPE0.equals(fontDictionary.getCOSName(COSName.SUBTYPE))
                && !fontDictionary.containsKey(COSName.TO_UNICODE);
    }
}
