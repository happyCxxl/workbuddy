package com.workbuddy.worker.preprocessing.impl;

import com.workbuddy.common.utils.NullUtil;

import java.text.Normalizer;

/**
 * 文本基座处理（编码规范化规则与文本整理规则共用，单一口径）：
 * tidyDisplay = 空白/换行/乱码整理（displayText 口径）；
 * normalizeBase = tidyDisplay + Unicode NFKC 规范化（normalizedText 基座，全角→半角由 NFKC 承担）。
 */
public final class TextBase {

    private TextBase() {
    }

    /**
     * 展示文本口径：全角空格转半角、控制字符转空格、空白折叠、未定义码点剔除、trim。
     *
     * @param raw 原始文本
     * @return 展示文本
     */
    public static String tidyDisplay(String raw) {
        if (NullUtil.isNull(raw) || raw.isEmpty()) {
            return raw;
        }
        String s = raw.replace('\u3000', ' ');
        s = s.replaceAll("[\\x00-\\x1F\\x7F]", " ");
        s = s.replaceAll(" {2,}", " ");
        s = stripUndefined(s);
        return s.trim();
    }

    /**
     * 检索文本基座：展示口径基础上再做 Unicode NFKC（全角转半角、兼容字符规整）。
     *
     * @param tidy 展示文本
     * @return 检索文本基座
     */
    public static String normalizeBase(String tidy) {
        if (NullUtil.isNull(tidy) || tidy.isEmpty()) {
            return tidy;
        }
        return Normalizer.normalize(tidy, Normalizer.Form.NFKC).trim();
    }

    private static String stripUndefined(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isDefined(cp)) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
