package com.workbuddy.worker.structure.impl.craft;

import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.structure.UnifiedPage;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.structure.PageMark;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.utils.TextUtil;
import com.workbuddy.worker.structure.StructureProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 重复识别的等价性证明：随机夹具上把新实现与"逐个比 Jaccard"的参照实现逐项对比，
 * 外加规模带边界用例与规模冒烟；用于保证剪枝、按需重建与指纹快车道不改变判定结果。
 */
class RepeatNoiseMarkerEquivalenceTest {

    private static final String[] PIECES = {
            "投标保证金", "为人民币", "叁佰万元整", "第一章", "总则", "评分项", "分值", "备注", "甲方", "乙方"};

    private StructureProperties properties;
    private RepeatNoiseMarkerImpl marker;

    @BeforeEach
    void setUp() {
        properties = new StructureProperties();
        marker = new RepeatNoiseMarkerImpl(properties);
    }

    // ---------------- 夹具 ----------------

    private UnifiedElement element(String id, UnifiedElementType type, Integer page, String text) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(type.name());
        element.setPage(page);
        element.setText(text);
        return element;
    }

    private List<UnifiedPage> pages(int... pageNumbers) {
        List<UnifiedPage> pages = new ArrayList<>();
        for (int pageNumber : pageNumbers) {
            UnifiedPage page = new UnifiedPage();
            page.setPageId("pg-" + pageNumber);
            page.setPageNumber(pageNumber);
            pages.add(page);
        }
        return pages;
    }

    private UnifiedDocument document(List<UnifiedElement> elements, List<UnifiedPage> pages) {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(elements);
        document.setPages(pages);
        return document;
    }

    private String randomText(Random random) {
        StringBuilder text = new StringBuilder();
        int pieces = random.nextInt(8);
        for (int i = 0; i < pieces; i++) {
            text.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return text.toString();
    }

    /** 随机文档：含完全相同与近似重复的文本，随机页码与空页，逼近真实分布 */
    private UnifiedDocument randomDocument(Random random, int round) {
        int pageCount = 1 + random.nextInt(8);
        List<UnifiedPage> pages = pages(java.util.stream.IntStream.rangeClosed(1, pageCount).toArray());
        List<UnifiedElement> elements = new ArrayList<>();
        List<String> pool = new ArrayList<>();
        int elementCount = random.nextInt(30);
        for (int i = 0; i < elementCount; i++) {
            UnifiedElementType type = random.nextBoolean()
                    ? UnifiedElementType.PARAGRAPH : UnifiedElementType.TITLE;
            int mode = random.nextInt(10);
            String text;
            if (mode < 4 || pool.isEmpty()) {
                text = randomText(random);
                pool.add(text);
            } else if (mode < 7) {
                text = pool.get(random.nextInt(pool.size()));
            } else {
                text = dropOneChar(pool.get(random.nextInt(pool.size())));
            }
            Integer page = random.nextInt(10) == 0 ? null : 1 + random.nextInt(pageCount);
            elements.add(element("e-" + round + "-" + i, type, page, text));
        }
        return document(elements, pages);
    }

    private String dropOneChar(String text) {
        return text.isEmpty() ? text : text.substring(0, text.length() - 1);
    }

    // ---------------- 参照实现（改动前的算法，逐字照抄） ----------------

    private List<Integer> referenceRepeatedPages(UnifiedDocument document) {
        Map<Integer, String> pageText = new HashMap<>();
        for (UnifiedElement element : document.getElements()) {
            if (element.getPage() != null && !element.getText().isBlank()) {
                pageText.merge(element.getPage(), element.getText(), String::concat);
            }
        }
        List<Integer> pageNumbers = pageText.keySet().stream().sorted().toList();
        List<Set<String>> shingles = new ArrayList<>();
        for (Integer pageNumber : pageNumbers) {
            shingles.add(TextUtil.bigramSet(pageText.get(pageNumber)));
        }
        List<Integer> repeated = new ArrayList<>();
        for (int i = 0; i < pageNumbers.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (TextUtil.jaccardSet(shingles.get(i), shingles.get(j)) > properties.getRepeatPageJaccard()) {
                    repeated.add(pageNumbers.get(i));
                    break;
                }
            }
        }
        return repeated;
    }

    private List<String> referenceRepeatedSegments(UnifiedDocument document) {
        List<Set<String>> seen = new ArrayList<>();
        List<String> repeated = new ArrayList<>();
        for (UnifiedElement element : document.getElements()) {
            if (!isTextElement(element) || element.getText() == null || element.getText().isBlank()
                    || element.getText().length() < properties.getRepeatSegmentMinLen()) {
                continue;
            }
            Set<String> current = TextUtil.bigramSet(element.getText());
            boolean isRepeated = false;
            for (Set<String> earlier : seen) {
                if (TextUtil.jaccardSet(current, earlier) > properties.getRepeatSegmentSimilarity()) {
                    isRepeated = true;
                    break;
                }
            }
            if (isRepeated) {
                repeated.add(element.getId());
            } else {
                seen.add(current);
            }
        }
        return repeated;
    }

    private boolean isTextElement(UnifiedElement element) {
        String type = element.getType();
        return UnifiedElementType.PARAGRAPH.name().equals(type) || UnifiedElementType.TITLE.name().equals(type);
    }

    // ---------------- 实际结果读取 ----------------

    private List<Integer> actualRepeatedPages(UnifiedDocument document) {
        List<Integer> repeated = new ArrayList<>();
        for (UnifiedPage page : document.getPages()) {
            if (page.getMarks() != null && page.getMarks().contains(PageMark.REPEATED_PAGE.name())) {
                repeated.add(page.getPageNumber());
            }
        }
        return repeated;
    }

    private List<String> actualRepeatedSegments(UnifiedDocument document) {
        List<String> repeated = new ArrayList<>();
        for (UnifiedElement element : document.getElements()) {
            if (element.getMarks() != null && element.getMarks().contains(ElementMark.REPEATED_SEGMENT.name())) {
                repeated.add(element.getId());
            }
        }
        return repeated;
    }

    // ---------------- 用例 ----------------

    /**
     * 守护 {@code randomizedFixturesShouldMatchReferenceImplementation}：该场景行为改动即失败。
     */
    @Test
    void randomizedFixturesShouldMatchReferenceImplementation() {
        Random random = new Random(20261005L);
        for (int round = 0; round < 80; round++) {
            UnifiedDocument document = randomDocument(random, round);
            List<Integer> expectedPages = referenceRepeatedPages(document);
            List<String> expectedSegments = referenceRepeatedSegments(document);

            var outcome = marker.mark(document);

            assertEquals(expectedPages, actualRepeatedPages(document), "重复页不一致 round=" + round);
            assertEquals(expectedSegments, actualRepeatedSegments(document), "重复段不一致 round=" + round);
            assertEquals(expectedPages.size(), outcome.getRepeatPageCount(), "重复页计数不一致 round=" + round);
            assertEquals(expectedSegments.size(), outcome.getRepeatSegmentCount(), "重复段计数不一致 round=" + round);
        }
    }

    /**
     * 守护 {@code jaccardExceedsShouldMatchJaccardSet}：该场景行为改动即失败。
     */
    @Test
    void jaccardExceedsShouldMatchJaccardSet() {
        Random random = new Random(7L);
        double[] thresholds = {0.5, 0.9, 0.95, 1.0};
        for (int round = 0; round < 500; round++) {
            Set<String> a = randomBigrams(random);
            Set<String> b = randomBigrams(random);
            for (double threshold : thresholds) {
                assertEquals(TextUtil.jaccardSet(a, b) > threshold,
                        TextUtil.jaccardExceeds(a, b, threshold),
                        "集合规模 " + a.size() + "/" + b.size() + "，阈值 " + threshold);
            }
        }
    }

    private Set<String> randomBigrams(Random random) {
        Set<String> set = new HashSet<>();
        int size = random.nextInt(30);
        for (int i = 0; i < size; i++) {
            set.add("g" + random.nextInt(20));
        }
        return set;
    }

    /**
     * 守护 {@code slightlyDifferentSizesShouldStillBeMarkedRepeated}：该场景行为改动即失败。
     */
    @Test
    void slightlyDifferentSizesShouldStillBeMarkedRepeated() {
        String text = "评分项与分值说明：本表用于记录各项评分标准、权重与备注信息，供评审小组统一口径使用；"
                + "各项分值按百分制折算后汇总，出现争议时以评审组长复核结论为准，并在备注列写明依据。";
        String near = text.substring(0, text.length() - 2) + "。";

        UnifiedDocument document = document(List.of(
                element("p1", UnifiedElementType.PARAGRAPH, 1, text),
                element("p2", UnifiedElementType.PARAGRAPH, 2, near)), pages(1, 2));

        List<Integer> expectedPages = referenceRepeatedPages(document);
        List<String> expectedSegments = referenceRepeatedSegments(document);
        var outcome = marker.mark(document);

        assertEquals(List.of(2), expectedPages);
        assertEquals(expectedPages, actualRepeatedPages(document));
        assertEquals(expectedSegments, actualRepeatedSegments(document));
        assertEquals(1, outcome.getRepeatPageCount());
        assertEquals(1, outcome.getRepeatSegmentCount());
    }

    /**
     * 守护 {@code muchDifferentSizesShouldNotBeMarkedRepeated}：该场景行为改动即失败。
     */
    @Test
    void muchDifferentSizesShouldNotBeMarkedRepeated() {
        String shortText = "投标保证金为人民币叁佰万元整，甲方应在合同签订后三十日内支付完毕。".repeat(2);
        String longText = shortText + "补充条款".repeat(60);

        UnifiedDocument document = document(List.of(
                element("p1", UnifiedElementType.PARAGRAPH, 1, shortText),
                element("p2", UnifiedElementType.PARAGRAPH, 2, longText)), pages(1, 2));

        var outcome = marker.mark(document);

        // 集合规模差数倍 ⇒ Jaccard 上界够不到阈值，两侧都不判重复（新实现不得因剪枝而误判）
        assertEquals(0, outcome.getRepeatPageCount());
        assertEquals(0, outcome.getRepeatSegmentCount());
        assertEquals(List.of(), actualRepeatedPages(document));
        assertEquals(List.of(), actualRepeatedSegments(document));
    }

    /**
     * 守护 {@code largeDocumentSmokeShouldMarkExpectedRepeats}：该场景行为改动即失败。
     */
    @Test
    void largeDocumentSmokeShouldMarkExpectedRepeats() {
        List<UnifiedElement> elements = new ArrayList<>();
        List<UnifiedPage> pages = pages(java.util.stream.IntStream.rangeClosed(1, 400).toArray());
        String shared = uniqueText(1);
        for (int pageNumber = 1; pageNumber <= 400; pageNumber++) {
            boolean duplicate = pageNumber == 200 || pageNumber == 350;
            elements.add(element("e-" + pageNumber, UnifiedElementType.PARAGRAPH, pageNumber,
                    duplicate ? shared : uniqueText(pageNumber)));
        }
        UnifiedDocument document = document(elements, pages);

        List<Integer> expectedPages = referenceRepeatedPages(document);
        var outcome = marker.mark(document);

        assertEquals(List.of(200, 350), expectedPages);
        assertEquals(expectedPages, actualRepeatedPages(document));
        assertEquals(2, outcome.getRepeatPageCount());
    }

    /** 按种子生成互不相似的长文本（页间无公共模板，避免"天然相似"污染规模用例） */
    private String uniqueText(int seed) {
        Random random = new Random(seed * 7919L);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            text.append((char) ('\u4e00' + random.nextInt(1500)));
        }
        return text.toString();
    }
}
