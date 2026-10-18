package com.workbuddy.worker.parser.pdf.model;

import java.util.List;

/**
 * 单页的行级轻量摘要（页号 + 页高 + 行摘要列表）：跨页页眉页脚识别的输入，
 * 与 {@link PageContent} 同口径但只带行文本与盒子。
 */
public record PageLines(int pageNo, double pageHeight, List<TextLine> lines) {

    /** 由页提取内容取摘要。 */
    public static PageLines of(PageContent page) {
        return new PageLines(page.pageNo(), page.pageHeight(),
                page.lines().stream().map(TextLine::of).toList());
    }
}
