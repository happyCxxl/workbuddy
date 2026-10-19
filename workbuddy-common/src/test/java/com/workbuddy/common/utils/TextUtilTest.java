package com.workbuddy.common.utils;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本口径单测。
 *
 * <p>守护两块公共口径：乱码判定（{@code isNonCommonChar}/{@code printableMojibakeRatio}，
 * 分母为非空白字符，正常书写系统不误判）与 Jaccard 相似度家族
 * （{@code jaccardCharSet}/{@code jaccardBigram}/{@code jaccardExceeds}，
 * 去重合并与重复页判定的公共分母）。
 */
class TextUtilTest {

    /** 投标文件里常见的非 ASCII 字符：全角标点、引号、圈号、项目符号、希腊字母、生僻字、假名、谚文 */
    private static final int[] NORMAL = {
            '，', '。', '、', '；', '：', '？', '！', '（', '）', '《', '》', '【', '】',
            '“', '”', '‘', '’', '「', '」', '〔', '〕', '—', '…', '·', '￥',
            '※', '①', '⑩', '•', '●', '◆', 'α', 'Ω', 'А', '∑', '≤', '≥', '°', '€', '％',
            'Ａ', '１', 'ｱ', 'あ', '한', 0x20000, 0x2A700, 0xF900
    };

    /**
     * 正常书写系统与符号不判乱码。
     *
     * <p>守护 {@code isNonCommonChar} 的白名单口径：把全角标点或假名判成乱码即失败。
     */
    @Test
    void normalScriptsAndSymbolsShouldNotBeGarbled() {
        for (int codePoint : NORMAL) {
            assertFalse(TextUtil.isNonCommonChar(codePoint),
                    () -> "码点 U+" + Integer.toHexString(codePoint) + " 被误判为乱码");
        }
        assertEquals(0.0, TextUtil.garbledRatio("投标报价（含税）“叁佰万元”※① α+β ≤ 5%"), 0.0001);
    }

    /**
     * 不可读码点判乱码：替换字符、私用区、未分配、控制字符。
     *
     * <p>守护 {@code isNonCommonChar} 的黑名单口径：漏掉任一区即失败。
     */
    @Test
    void unreadableCodePointsShouldBeGarbled() {
        int[] garbled = {0xFFFD, 0xE000, 0xF8FF, 0xF0000, 0x10FFFD, 0x0378, 0x01, 0x9F};
        for (int codePoint : garbled) {
            assertTrue(TextUtil.isNonCommonChar(codePoint),
                    () -> "码点 U+" + Integer.toHexString(codePoint) + " 漏判");
        }
    }

    /**
     * 乱码率分母为非空白字符。
     *
     * <p>守护 {@code garbledRatio} 的分母口径：空白进分母会把 1/3 算成 2/7 即失败。
     */
    @Test
    void garbledRatioShouldExcludeWhitespaceFromDenominator() {
        // 2 个替换字符 + 4 个正常字符 + 1 个空白：2/6；若空白进分母会算成 2/7
        assertEquals(1.0 / 3.0, TextUtil.garbledRatio("\uFFFD\uFFFD正常 文本"), 0.0001);
    }

    /**
     * 空文本与纯空白文本的乱码率与可打印乱码率均为 0（分母为零的兜底）。
     *
     * <p>守护 {@code garbledRatio}/{@code printableMojibakeRatio} 的零分母兜底：
     * 去掉兜底即失败（除零得 NaN）。
     */
    @Test
    void ratiosShouldReturnZeroForEmptyOrBlankText() {
        assertEquals(0.0, TextUtil.garbledRatio(null), 0.0001);
        assertEquals(0.0, TextUtil.garbledRatio(""), 0.0001);
        assertEquals(0.0, TextUtil.garbledRatio("   "), 0.0001);
        assertEquals(0.0, TextUtil.printableMojibakeRatio(null), 0.0001);
        assertEquals(0.0, TextUtil.printableMojibakeRatio(""), 0.0001);
        assertEquals(0.0, TextUtil.printableMojibakeRatio("   "), 0.0001);
    }

    /**
     * 可打印乱码带按拉丁补充区边界判定。
     *
     * <p>守护 {@code isPrintableMojibakeChar} 的区间边界：把 U+009F 或 U+0100 算进带内即失败。
     */
    @Test
    void printableMojibakeBandShouldFollowLatinSupplementBoundary() {
        assertFalse(TextUtil.isPrintableMojibakeChar(0x9F));
        assertTrue(TextUtil.isPrintableMojibakeChar(0xA0));
        assertTrue(TextUtil.isPrintableMojibakeChar(0xFF));
        assertFalse(TextUtil.isPrintableMojibakeChar(0x100));
    }

    /**
     * 可打印乱码率抓字体映射错位页（整行落在拉丁补充区）。
     *
     * <p>守护 {@code printableMojibakeRatio}：正常正文里该区占比恒低，整行命中即错位页。
     */
    @Test
    void printableMojibakeRatioShouldCatchLatinSupplementBand() {
        assertEquals(1.0, TextUtil.printableMojibakeRatio("ÿþ¤¥¦§¨©"), 0.0001);
        assertEquals(0.0, TextUtil.printableMojibakeRatio("投标报价（含税）叁佰万元"), 0.0001);
        assertEquals(0.0, TextUtil.printableMojibakeRatio("Bidding price 30"), 0.0001);
        assertEquals(1.0 / 9.0, TextUtil.printableMojibakeRatio("café 30 EUR"), 0.0001);
    }

    /**
     * 字符集相似度：双空白视为同一，单空白为零，空白不参与集合。
     *
     * <p>守护 {@code jaccardCharSet} 的空文本口径与空白过滤。
     */
    @Test
    void jaccardCharSetShouldHandleBlankAndWhitespace() {
        assertEquals(1.0, TextUtil.jaccardCharSet("", ""), 0.0001);
        assertEquals(1.0, TextUtil.jaccardCharSet(" ", "  "), 0.0001);
        assertEquals(0.0, TextUtil.jaccardCharSet("abc", ""), 0.0001);
        assertEquals(1.0, TextUtil.jaccardCharSet("a b c", "abc"), 0.0001);
    }

    /**
     * 字符集相似度按去重字符集计算。
     *
     * <p>守护 {@code jaccardCharSet}：交集 ÷ 并集，把并集写成合集大小即失败。
     */
    @Test
    void jaccardCharSetShouldComputeIntersectionOverUnion() {
        assertEquals(0.75, TextUtil.jaccardCharSet("abc", "abcd"), 0.0001);
        assertEquals(0.0, TextUtil.jaccardCharSet("abc", "xyz"), 0.0001);
    }

    /**
     * bigram 集合按滑窗去重。
     *
     * <p>守护 {@code bigramSet}：窗口或去重写错即失败（重复页判定失真）。
     */
    @Test
    void bigramSetShouldSlideAndDeduplicate() {
        assertEquals(Set.of("ab", "bc", "cd"), TextUtil.bigramSet("abcd"));
        assertEquals(Set.of("ab", "ba"), TextUtil.bigramSet("abab"));
        assertTrue(TextUtil.bigramSet("").isEmpty());
        assertTrue(TextUtil.bigramSet("a").isEmpty());
    }

    /**
     * 集合相似度：双空集视为不相似，相同集合为 1。
     *
     * <p>守护 {@code jaccardSet} 的空集口径（与字符集口径相反，属刻意约定）。
     */
    @Test
    void jaccardSetShouldTreatDoubleEmptyAsDissimilar() {
        assertEquals(0.0, TextUtil.jaccardSet(Set.of(), Set.of()), 0.0001);
        assertEquals(1.0, TextUtil.jaccardSet(Set.of("a"), Set.of("a")), 0.0001);
        assertEquals(0.0, TextUtil.jaccardSet(Set.of("a"), Set.of("b")), 0.0001);
        // 交集 {ab} ÷ 并集 {ab,bc,ce} = 1/3
        assertEquals(1.0 / 3.0, TextUtil.jaccardSet(Set.of("ab", "bc"), Set.of("ab", "ce")), 0.0001);
    }

    /**
     * bigram 相似度按二元组集合计算。
     *
     * <p>守护 {@code jaccardBigram}：与手算交集并集一致即通过。
     */
    @Test
    void jaccardBigramShouldFollowSetSemantics() {
        assertEquals(1.0, TextUtil.jaccardBigram("abcd", "abcd"), 0.0001);
        // 交集 {ab,bc}，并集 {ab,bc,cd,ce} → 0.5
        assertEquals(0.5, TextUtil.jaccardBigram("abcd", "abce"), 0.0001);
    }

    /**
     * bigram 摘要：个数与集合一致，指纹次序无关。
     *
     * <p>守护 {@code bigramSummary}：同一 bigram 集合的不同文本指纹必须相等，
     * 指纹改次序相关即失败（重复快车道误判）。
     */
    @Test
    void bigramSummaryShouldBeOrderInsensitive() {
        TextUtil.BigramSummary first = TextUtil.bigramSummary("abcabc");
        TextUtil.BigramSummary second = TextUtil.bigramSummary("cabcab");
        assertEquals(3, first.count());
        assertEquals(first.count(), second.count());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertNotEquals(first.fingerprint(), TextUtil.bigramSummary("xyz").fingerprint());
    }

    /**
     * 相似度严格超阈值：恰好等于阈值不算超过。
     *
     * <p>守护 {@code jaccardExceeds} 的严格口径：把「严格大于」写成「不小于」即失败。
     */
    @Test
    void jaccardExceedsShouldBeStrictlyGreaterThanThreshold() {
        // 交集 1 ÷ 并集 2 = 恰好 0.5
        assertFalse(TextUtil.jaccardExceeds(Set.of("x"), Set.of("x", "y"), 0.5));
        assertTrue(TextUtil.jaccardExceeds(Set.of("x", "y"), Set.of("x", "y"), 0.5));
        assertFalse(TextUtil.jaccardExceeds(Set.of("x"), Set.of("y"), 0.5));
        assertFalse(TextUtil.jaccardExceeds(Set.of(), Set.of(), 0.5));
    }

    /**
     * 相似度判定的提前中止与全集计算一致。
     *
     * <p>守护 {@code jaccardExceeds} 的提前中止剪枝：剪枝返回与精确计算不一致即失败。
     */
    @Test
    void jaccardExceedsShouldAgreeWithExactComputation() {
        Set<String> small = Set.of("ab", "bc", "cd");
        Set<String> large = Set.of("ab", "bc", "cd", "de", "ef", "fg", "gh", "hi");
        // 交集 3 ÷ 并集 8 = 0.375
        assertEquals(TextUtil.jaccardSet(small, large) > 0.3,
                TextUtil.jaccardExceeds(small, large, 0.3));
        assertFalse(TextUtil.jaccardExceeds(small, large, 0.5));
    }
}
