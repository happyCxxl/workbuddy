package com.workbuddy.worker.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定窗口切分工具：len + 重叠 overlap 硬切（正文固定窗口算法与兜底共用口径；不足一片按实际长度）。
 */
public final class WindowSlicer {

    private WindowSlicer() {
    }

    /** 固定窗口切分（len + overlap；不足一片按实际长度） */
    public static List<String> slice(String text, int len, int overlap) {
        if (text.length() <= len) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        int step = Math.max(1, len - overlap);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + len);
            pieces.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start += step;
        }
        return pieces;
    }
}
