package com.workbuddy.worker.parser.support;

import com.workbuddy.common.utils.NullUtil;

import cn.hutool.core.util.StrUtil;

/**
 * 目录行级特征判定（PDF 行 / DOCX 段落共用，单一口径）：
 * 行尾页码（阿拉伯数字或中文数字）+ 点线引导符（连续点/省略号/长空白）。
 * 只标特征行，"整页判目录页"聚合在预处理环节。
 *
 * @author cxxl
 */
public final class TocLineFeature {

    private TocLineFeature() {
    }

    /**
     * 是否目录行：行尾为页码且正文与页码之间存在点线引导符或长空白。
     */
    public static boolean isTocLine(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String t = text.trim();
        String trailing = trailingNumber(t);
        if (NullUtil.isNull(trailing)) {
            return false;
        }
        // 正文与页码之间的部分不 trim（长空白引导符要靠尾部空白判定）
        String body = t.substring(0, t.length() - trailing.length());
        if (body.isBlank()) {
            return false;
        }
        // 点线引导符：省略号 / 连续 ≥3 个点或点分隔 / ≥4 连续空白
        if (body.contains("…") || body.contains("……")) {
            return true;
        }
        if (body.matches(".*[.．]{3,}.*")) {
            return true;
        }
        if (body.matches(".*[.．]\\s*[.．]\\s*[.．].*")) {
            return true;
        }
        return body.matches(".*\\s{4,}$");
    }

    /** 行尾页码（1~4 位阿拉伯数字或中文数字）；非页码返回 null */
    private static String trailingNumber(String text) {
        if (text.matches(".*\\d{1,4}$")) {
            return text.replaceAll("^.*?(\\d{1,4})$", "$1");
        }
        if (text.matches(".*[一二三四五六七八九十百]{1,6}$")) {
            return text.replaceAll("^.*?([一二三四五六七八九十百]{1,6})$", "$1");
        }
        return null;
    }
}
