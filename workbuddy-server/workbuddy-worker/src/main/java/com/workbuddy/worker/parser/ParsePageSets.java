package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.utils.NullUtil;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 解析页码集合工具：统一扫描页与 OCR 覆盖页的口径，供门禁评估与摘要共用。
 */
final class ParsePageSets {

    private ParsePageSets() {
    }

    /** 扫描页去掉空白页：空白页无内容可恢复，不参与 OCR 覆盖判定。 */
    static Set<Integer> scannedPages(QualityInfo quality, Set<Integer> blankPages) {
        return quality.getScannedPages().stream()
                .filter(page -> !blankPages.contains(page))
                .collect(Collectors.toSet());
    }

    /** OCR 路已产出文字的页码集合（无 OCR 能力或元素无页码时为空）。 */
    static Set<Integer> ocrPages(ParseSource ocrSource) {
        if (NullUtil.isNull(ocrSource)) {
            return Set.of();
        }
        return ocrSource.getElements().stream()
                .map(ParseElement::getPage)
                .filter(NullUtil::isNotNull)
                .collect(Collectors.toSet());
    }
}
