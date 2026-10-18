package com.workbuddy.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 乱码口径单测：正常书写系统与符号不判乱码；替换字符/私用区/未分配码点判乱码；分母为非空白字符。
 */
class TextUtilTest {

    /** 投标文件里常见的非 ASCII 字符：全角标点、引号、圈号、项目符号、希腊字母、生僻字、假名、谚文 */
    private static final int[] NORMAL = {
            '，', '。', '、', '；', '：', '？', '！', '（', '）', '《', '》', '【', '】',
            '“', '”', '‘', '’', '「', '」', '〔', '〕', '—', '…', '·', '￥',
            '※', '①', '⑩', '•', '●', '◆', 'α', 'Ω', 'А', '∑', '≤', '≥', '°', '€', '％',
            'Ａ', '１', 'ｱ', 'あ', '한', 0x20000, 0x2A700, 0xF900
    };

    @Test
    void normalScriptsAndSymbolsShouldNotBeGarbled() {
        for (int codePoint : NORMAL) {
            assertFalse(TextUtil.isNonCommonChar(codePoint),
                    () -> "码点 U+" + Integer.toHexString(codePoint) + " 被误判为乱码");
        }
        assertEquals(0.0, TextUtil.garbledRatio("投标报价（含税）“叁佰万元”※① α+β ≤ 5%"), 0.0001);
    }

    @Test
    void unreadableCodePointsShouldBeGarbled() {
        int[] garbled = {0xFFFD, 0xE000, 0xF8FF, 0x10FFFD, 0x0378, 0x01, 0x9F};
        for (int codePoint : garbled) {
            assertTrue(TextUtil.isNonCommonChar(codePoint),
                    () -> "码点 U+" + Integer.toHexString(codePoint) + " 漏判");
        }
    }

    @Test
    void garbledRatioShouldExcludeWhitespaceFromDenominator() {
        // 2 个替换字符 + 4 个正常字符 + 1 个空白：2/6；若空白进分母会算成 2/7
        assertEquals(1.0 / 3.0, TextUtil.garbledRatio("\uFFFD\uFFFD正常 文本"), 0.0001);
    }

    @Test
    void printableMojibakeRatioShouldCatchLatinSupplementBand() {
        // 字体映射错位出的可打印乱码：整行落在拉丁补充区
        assertEquals(1.0, TextUtil.printableMojibakeRatio("ÿþ¤¥¦§¨©"), 0.0001);
        // 中文与西文正文里该区占比很低（空白不进分母）
        assertEquals(0.0, TextUtil.printableMojibakeRatio("投标报价（含税）叁佰万元"), 0.0001);
        assertEquals(0.0, TextUtil.printableMojibakeRatio("Bidding price 30"), 0.0001);
        assertEquals(1.0 / 9.0, TextUtil.printableMojibakeRatio("café 30 EUR"), 0.0001);
    }
}
