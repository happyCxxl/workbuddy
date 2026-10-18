package com.workbuddy.worker.structure.impl.craft;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.structure.UnifiedPage;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.structure.PageMark;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.TextUtil;
import com.workbuddy.worker.structure.StructureProperties;
import com.workbuddy.worker.structure.craft.MarkOutcome;
import com.workbuddy.worker.structure.craft.RepeatNoiseMarker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 重复/噪声识别实现（字符 bigram Jaccard 相似度）：
 * 重复页——PDF 页文本聚合与前面页面比较 > repeatPageJaccard（除首份外标 REPEATED_PAGE）；
 * 重复段——文本元素（排除 HEADER/FOOTER）与前面元素比较 > repeatSegmentSimilarity
 * 且长度 ≥ repeatSegmentMinLen（除首份外标 REPEATED_SEGMENT）；
 * 噪声页——无元素页/仅图片页/乱码率 > noiseGarbledRatio（标 NOISE_PAGE）；页覆盖范围按 pageRange 计，跨页表覆盖到的页不判空白。
 * Word（无页概念）跳过页级、重复段照做；标记只增不改结构。
 * 判定语义与"逐个比 Jaccard"一致：已见项按集合规模分桶剪枝、集合按需重建（不常驻）、有界 LRU 控内存。
 */
@Component
@RequiredArgsConstructor
public class RepeatNoiseMarkerImpl implements RepeatNoiseMarker {

    private final StructureProperties properties;

    /**
     * 标记跨页重复元素与噪声页元素。
     *
     * @param document 统一文档
     * @return 标记结果
     */
    @Override
    public MarkOutcome mark(UnifiedDocument document) {
        MarkOutcome outcome = new MarkOutcome();
        List<UnifiedElement> elements = ObjectUtil.defaultIfNull(document.getElements(), new ArrayList<>());

        markRepeatedPages(document, outcome);
        markRepeatedSegments(elements, outcome);
        markNoisePages(document, elements, outcome);
        return outcome;
    }

    // ---------------- 重复页（PDF，有页概念） ----------------

    private void markRepeatedPages(UnifiedDocument document, MarkOutcome outcome) {
        List<UnifiedPage> pages = document.getPages();
        if (NullUtil.isNull(pages) || pages.isEmpty()) {
            return;
        }
        // 页 → 元素引用：拼接顺序与既有口径一致（只取元素自身页码），页文本与 bigram 集合都不常驻（按需重建）
        Map<Integer, List<UnifiedElement>> pageElements = new HashMap<>();
        for (UnifiedElement element : document.getElements()) {
            // 重复页指纹只取元素自身页码：跨页元素的同一段文字计入其覆盖的每一页，会让这些页互判重复
            if (NullUtil.isNotNull(element.getPage()) && StrUtil.isNotBlank(element.getText())) {
                pageElements.computeIfAbsent(element.getPage(), key -> new ArrayList<>()).add(element);
            }
        }
        List<Integer> pageNumbers = pageElements.keySet().stream().sorted().toList();
        RepeatScan scan = new RepeatScan(properties.getRepeatSetCacheSize());
        for (Integer pageNumber : pageNumbers) {
            Supplier<String> textSource = () -> concatText(pageElements.get(pageNumber));
            // 与"与该页之前的所有页逐个比 Jaccard > 阈值"等价：集合规模带之外的页不可能达标
            if (scan.isDuplicate(pageNumber, textSource, properties.getRepeatPageJaccard(), true)) {
                addPageMark(pages, pageNumber, PageMark.REPEATED_PAGE);
                outcome.setRepeatPageCount(outcome.getRepeatPageCount() + 1);
            }
        }
    }

    /** 页文本：按文档顺序拼接该页元素文本（空文本元素不参与拼接） */
    private static String concatText(List<UnifiedElement> elements) {
        StringBuilder text = new StringBuilder();
        for (UnifiedElement element : elements) {
            text.append(element.getText());
        }
        return text.toString();
    }

    // ---------------- 重复段 ----------------

    private void markRepeatedSegments(List<UnifiedElement> elements, MarkOutcome outcome) {
        RepeatScan scan = new RepeatScan(properties.getRepeatSetCacheSize());
        for (UnifiedElement element : elements) {
            if (!isTextElement(element) || StrUtil.isBlank(element.getText())
                    || element.getText().length() < properties.getRepeatSegmentMinLen()) {
                continue;
            }
            // 只有未判重复的元素才成为后续参照；集合按需从元素文本重建、不常驻
            if (scan.isDuplicate(element, element::getText, properties.getRepeatSegmentSimilarity(), false)) {
                addElementMark(element, ElementMark.REPEATED_SEGMENT);
                outcome.setRepeatSegmentCount(outcome.getRepeatSegmentCount() + 1);
            }
        }
    }

    /** 元素覆盖的页码集合：跨页元素的覆盖范围由 pageRange 表达，单页元素即 page 本身 */
    private List<Integer> pagesOf(UnifiedElement element) {
        List<Integer> pageRange = element.getPageRange();
        if (NullUtil.isNotNull(pageRange) && !pageRange.isEmpty()) {
            return pageRange;
        }
        return NullUtil.isNotNull(element.getPage()) ? List.of(element.getPage()) : List.of();
    }

    /** 文本元素：PARAGRAPH/TITLE（排除 HEADER/FOOTER——页眉页脚有独立处置路径） */
    private boolean isTextElement(UnifiedElement element) {
        String type = element.getType();
        return UnifiedElementType.PARAGRAPH.name().equals(type) || UnifiedElementType.TITLE.name().equals(type);
    }

    // ---------------- 噪声页（PDF） ----------------

    private void markNoisePages(UnifiedDocument document, List<UnifiedElement> elements, MarkOutcome outcome) {
        List<UnifiedPage> pages = document.getPages();
        if (NullUtil.isNull(pages) || pages.isEmpty()) {
            return;
        }
        Map<Integer, List<UnifiedElement>> byPage = new HashMap<>();
        for (UnifiedElement element : elements) {
            for (Integer pageNumber : pagesOf(element)) {
                byPage.computeIfAbsent(pageNumber, k -> new ArrayList<>()).add(element);
            }
        }
        for (UnifiedPage page : pages) {
            List<UnifiedElement> pageElements = byPage.getOrDefault(page.getPageNumber(), new ArrayList<>());
            boolean noise;
            String reason;
            if (pageElements.isEmpty()) {
                noise = true;
                reason = "空白页（无任何元素）";
            } else if (pageElements.stream().allMatch(e -> UnifiedElementType.IMAGE.name().equals(e.getType()))) {
                noise = true;
                reason = "纯图片占位页（无文字）";
            } else {
                String text = pageElements.stream()
                        .map(UnifiedElement::getText)
                        .filter(StrUtil::isNotBlank)
                        .reduce("", String::concat);
                noise = TextUtil.garbledRatio(text) > properties.getNoiseGarbledRatio();
                reason = "乱码率超阈值页";
            }
            if (noise) {
                addPageMark(pages, page.getPageNumber(), PageMark.NOISE_PAGE);
                outcome.setNoisePageCount(outcome.getNoisePageCount() + 1);
                outcome.getWarnings().add("页 " + page.getPageNumber() + " " + reason);
            }
        }
    }

    // ---------------- 公共 ----------------

    private void addPageMark(List<UnifiedPage> pages, Integer pageNumber, PageMark mark) {
        for (UnifiedPage page : pages) {
            if (Objects.equals(page.getPageNumber(), pageNumber)) {
                if (NullUtil.isNull(page.getMarks())) {
                    page.setMarks(new ArrayList<>());
                }
                if (!page.getMarks().contains(mark.name())) {
                    page.getMarks().add(mark.name());
                }
                return;
            }
        }
    }

    private void addElementMark(UnifiedElement element, ElementMark mark) {
        if (NullUtil.isNull(element.getMarks())) {
            element.setMarks(new ArrayList<>());
        }
        if (!element.getMarks().contains(mark.name())) {
            element.getMarks().add(mark.name());
        }
    }

    /**
     * 重复扫描（结果与"与之前所有项逐个比 Jaccard > 阈值"等价）：
     * 已见项按 bigram 集合规模分桶（Jaccard 上界 = min/max，故只比规模带内的候选）；
     * 集合不常驻——按需从原文重建，配容量有界的 LRU；先走"指纹相同 + 集合相等"的精确快车道。
     */
    private static final class RepeatScan {

        private final int cacheSize;
        private final NavigableMap<Integer, List<SeenItem>> bySize = new TreeMap<>();
        private final Map<Long, List<SeenItem>> byFingerprint = new HashMap<>();
        private final Map<Object, Set<String>> setCache;

        RepeatScan(int cacheSize) {
            this.cacheSize = Math.max(1, cacheSize);
            this.setCache = new LinkedHashMap<>(16, 0.75f, true) {
                /**
                 * LRU 淘汰：缓存条目超过上限即移除最久未用的那条。
                 *
                 * @param eldest 最久未使用的条目
                 * @return 需要淘汰为 true
                 */
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, Set<String>> eldest) {
                    return size() > RepeatScan.this.cacheSize;
                }
            };
        }

        /**
         * 当前项是否与任一已见项判为重复。
         *
         * @param key            项标识（页号或元素），用于缓存与重建
         * @param textSource     文本来源（按需重建集合）
         * @param threshold      Jaccard 阈值（严格大于才判重复）
         * @param rememberAlways 是否无条件记为参照（页路径：所有页；段路径：只记未判重复的）
         */
        boolean isDuplicate(Object key, Supplier<String> textSource, double threshold, boolean rememberAlways) {
            TextUtil.BigramSummary summary = TextUtil.bigramSummary(textSource.get());
            if (summary.count() == 0) {
                // 空集合与谁都不相似（jaccardSet 对空集返回 0）
                return false;
            }
            Set<String> current = set(key, textSource);
            if (threshold < 1 && matchesFingerprint(summary.fingerprint(), current)) {
                // 集合完全相同 ⇒ Jaccard = 1 > 阈值，与原判定一致
                return true;
            }
            boolean duplicate = exceedsInBand(summary.count(), threshold, current);
            if (rememberAlways || !duplicate) {
                remember(key, summary, textSource);
            }
            return duplicate;
        }

        private boolean matchesFingerprint(long fingerprint, Set<String> current) {
            List<SeenItem> candidates = byFingerprint.get(fingerprint);
            if (NullUtil.isNull(candidates)) {
                return false;
            }
            for (SeenItem candidate : candidates) {
                if (set(candidate.key(), candidate.textSource()).equals(current)) {
                    return true;
                }
            }
            return false;
        }

        private boolean exceedsInBand(int size, double threshold, Set<String> current) {
            if (bySize.isEmpty()) {
                return false;
            }
            // Jaccard ≤ min/max ⇒ 规模带 [size × 阈值, size ÷ 阈值] 之外不可能达标
            int min = (int) Math.ceil(size * threshold);
            int max = threshold <= 0 ? Integer.MAX_VALUE : (int) Math.floor(size / threshold);
            if (min > max) {
                return false;
            }
            for (List<SeenItem> bucket : bySize.subMap(min, true, max, true).values()) {
                for (SeenItem candidate : bucket) {
                    Set<String> candidateSet = set(candidate.key(), candidate.textSource());
                    if (TextUtil.jaccardExceeds(current, candidateSet, threshold)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void remember(Object key, TextUtil.BigramSummary summary, Supplier<String> textSource) {
            SeenItem item = new SeenItem(key, summary.fingerprint(), textSource);
            bySize.computeIfAbsent(summary.count(), bucket -> new ArrayList<>()).add(item);
            byFingerprint.computeIfAbsent(summary.fingerprint(), bucket -> new ArrayList<>()).add(item);
        }

        private Set<String> set(Object key, Supplier<String> textSource) {
            Set<String> cached = setCache.get(key);
            if (NullUtil.isNotNull(cached)) {
                return cached;
            }
            Set<String> built = TextUtil.bigramSet(textSource.get());
            setCache.put(key, built);
            return built;
        }

        /** 已见项：标识 + 集合指纹 + 文本来源（集合本身不常驻） */
        private record SeenItem(Object key, long fingerprint, Supplier<String> textSource) {
        }
    }
}
