package com.workbuddy.worker.parser.pdf.detect;

import java.util.Set;

/**
 * 跨页页眉/页脚识别结果。
 *
 * @param headers 页眉文本键
 * @param footers 页脚文本键与页码中心
 */
public record HeaderFooterKeys(Set<String> headers, HeaderFooterDetector.FooterKeys footers) {
}
