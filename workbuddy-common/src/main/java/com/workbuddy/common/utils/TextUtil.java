package com.workbuddy.common.utils;

import java.util.HashSet;
import java.util.Set;

/**
 * 文本工具：乱码率判定与 Jaccard 相似度的公共口径（解析/组装/预处理环节共用，避免各写一套）。
 *
 * @author cxxl
 */
public final class TextUtil {

    private TextUtil() {
    }

    /**
     * 是否乱码字符：只认**不可读**的码点 —— 替换字符（U+FFFD）、私用区、未分配码点、控制字符。
     * 全角标点与引号、假名、谚文、生僻字（CJK 扩展 A 及以上）、希腊/西里尔字母、数学符号、
     * 圈号与项目符号等一律算正常字符。
     * 已知盲区：字体缺 ToUnicode 时错位出的可打印 ASCII 垃圾码点合法，不在本判据覆盖内。
     */
    public static boolean isNonCommonChar(int codePoint) {
        if (codePoint == 0xFFFD) {
            return true;
        }
        if ((codePoint >= 0xE000 && codePoint <= 0xF8FF) || codePoint >= 0xF0000) {
            return true;
        }
        if (codePoint < 0x20 || (codePoint >= 0x7F && codePoint <= 0x9F)) {
            return true;
        }
        return !Character.isDefined(codePoint);
    }

    /**
     * 是否可打印乱码带字符：拉丁补充区（U+00A0–U+00FF）。
     * 字体缺 Unicode 映射时错位出的码点（`ÿþ¤¥¦§¨©`）集中落在此区，而自然语言正文里该区占比很低。
     */
    public static boolean isPrintableMojibakeChar(int codePoint) {
        return codePoint >= 0xA0 && codePoint <= 0xFF;
    }

    /**
     * 可打印乱码率：可打印乱码带码点数 ÷ 非空白码点总数（无有效字符时返回 0）。
     * 与 {@link #garbledRatio(String)} 同一分母口径（非空白字符），供字体映射错位页的判定使用。
     */
    public static double printableMojibakeRatio(String text) {
        if (NullUtil.isNull(text) || text.isEmpty()) {
            return 0;
        }
        int nonBlank = 0;
        int mojibake = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                nonBlank++;
                if (isPrintableMojibakeChar(cp)) {
                    mojibake++;
                }
            }
            i += Character.charCount(cp);
        }
        return nonBlank > 0 ? (double) mojibake / nonBlank : 0;
    }

    /**
     * 文本乱码率：非常用字符码点数 ÷ 非空白码点总数（无有效字符时返回 0）。
     */
    public static double garbledRatio(String text) {
        if (NullUtil.isNull(text) || text.isEmpty()) {
            return 0;
        }
        int nonBlank = 0;
        int nonCommon = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                nonBlank++;
                if (isNonCommonChar(cp)) {
                    nonCommon++;
                }
            }
            i += Character.charCount(cp);
        }
        return nonBlank > 0 ? (double) nonCommon / nonBlank : 0;
    }

    /**
     * 字符集 Jaccard 相似度（忽略空白字符）：去重合并与续表表头判定的公共口径。
     * 两端均为空文本视为同一（返回 1）。
     */
    public static double jaccardCharSet(String a, String b) {
        if (a.isBlank() && b.isBlank()) {
            return 1;
        }
        if (a.isBlank() || b.isBlank()) {
            return 0;
        }
        Set<Character> setA = new HashSet<>();
        a.chars().filter(c -> !Character.isWhitespace(c)).forEach(c -> setA.add((char) c));
        Set<Character> setB = new HashSet<>();
        b.chars().filter(c -> !Character.isWhitespace(c)).forEach(c -> setB.add((char) c));
        return jaccardRatio(setA, setB);
    }

    /**
     * bigram 集合相似度（字符二元组 Jaccard）：重复页/重复段判定的公共口径。
     * 两端均为空集合视为不相似（返回 0）。
     */
    public static double jaccardBigram(String a, String b) {
        return jaccardSet(bigramSet(a), bigramSet(b));
    }

    /** 文本 → 字符二元组集合（含空文本 → 空集合） */
    public static Set<String> bigramSet(String text) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            set.add(text.substring(i, i + 2));
        }
        return set;
    }

    /** 集合 Jaccard 相似度（两端同为空集视为不相似，返回 0） */
    public static double jaccardSet(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    /**
     * bigram 集合摘要：去重后的 bigram 个数（与 {@link #bigramSet} 的 size 一致）+ 次序无关指纹。
     * 供"按集合规模剪枝 + 精确重复快车道"使用，集合本身瞬时释放、不常驻。
     */
    public record BigramSummary(int count, long fingerprint) {
    }

    /** 文本 → bigram 摘要（内部集合瞬时释放） */
    public static BigramSummary bigramSummary(String text) {
        Set<String> bigrams = bigramSet(text);
        long fingerprint = 0;
        for (String bigram : bigrams) {
            fingerprint += mix(bigram.hashCode());
        }
        return new BigramSummary(bigrams.size(), fingerprint);
    }

    /**
     * 集合 Jaccard 是否严格超过阈值：判定与 {@code jaccardSet(a, b) > threshold} 一致，
     * 但用"达标所需的最小交集"提前中止——剩余元素全部命中也不够时立即返回 false。
     */
    public static boolean jaccardExceeds(Set<String> a, Set<String> b, double threshold) {
        if (a.isEmpty() && b.isEmpty()) {
            return false;
        }
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        double required = threshold * (a.size() + b.size()) / (1 + threshold);
        int hits = 0;
        int remaining = smaller.size();
        for (String bigram : smaller) {
            remaining--;
            if (larger.contains(bigram)) {
                hits++;
            }
            if (hits + remaining <= required) {
                return false;
            }
        }
        int union = a.size() + b.size() - hits;
        return union > 0 && (double) hits / union > threshold;
    }

    /** 次序无关的 64 位指纹单元（只作候选筛选，命中后仍需集合相等复核） */
    private static long mix(int value) {
        long mixed = value * 0x9E3779B97F4A7C15L;
        return mixed ^ (mixed >>> 29);
    }

    /** 集合 Jaccard 相似度（两端同为空集视为同一，返回 1） */
    private static double jaccardRatio(Set<?> a, Set<?> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        Set<Object> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<Object> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 1 : (double) intersection.size() / union.size();
    }
}
