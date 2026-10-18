package com.workbuddy.worker.structure.impl.craft;

import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.structure.UnifiedPage;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.structure.PageMark;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.worker.structure.StructureProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重复/噪声识别单测：重复页（首份不标）/重复段（长度门槛、排除页眉页脚）/噪声页（空白/纯图片/乱码）。
 */
class RepeatNoiseMarkerImplTest {

    private RepeatNoiseMarkerImpl marker;

    @BeforeEach
    void setUp() {
        marker = new RepeatNoiseMarkerImpl(new StructureProperties());
    }

    private UnifiedElement element(String id, String type, Integer page, String text) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(type);
        element.setPage(page);
        element.setText(text);
        return element;
    }

    private UnifiedDocument document(List<UnifiedElement> elements, List<UnifiedPage> pages) {
        UnifiedDocument document = new UnifiedDocument();
        DocumentInfo info = new DocumentInfo();
        info.setDocumentId("doc-1");
        document.setDocumentInfo(info);
        document.setElements(elements);
        document.setPages(pages);
        return document;
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

    @Test
    void identicalPageShouldMarkLaterPageRepeated() {
        String text = "这是一段用于重复页判定测试的正文段落文本，其长度必须超过五十个字符的最小阈值要求，"
                + "因此这里补充了足够多的内容以确保判定逻辑能够正确命中并完成标记。";
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("p1", UnifiedElementType.PARAGRAPH.name(), 1, text));
        elements.add(element("p2", UnifiedElementType.PARAGRAPH.name(), 2, text));
        UnifiedDocument document = document(elements, pages(1, 2));

        var outcome = marker.mark(document);

        assertEquals(1, outcome.getRepeatPageCount());
        assertEquals(1, outcome.getRepeatSegmentCount());
        assertTrue(document.getPages().get(0).getMarks() == null
                || !document.getPages().get(0).getMarks().contains(PageMark.REPEATED_PAGE.name()));
        assertTrue(document.getPages().get(1).getMarks().contains(PageMark.REPEATED_PAGE.name()));
        assertTrue(elements.get(1).getMarks().contains(ElementMark.REPEATED_SEGMENT.name()));
        assertNull(elements.get(0).getMarks());
    }

    @Test
    void distinctPagesShouldNotMarkRepeated() {
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("p1", UnifiedElementType.PARAGRAPH.name(), 1, "第一章 招标公告与投标邀请书的全部正文内容段落一"));
        elements.add(element("p2", UnifiedElementType.PARAGRAPH.name(), 2, "第二章 评标办法与评分标准细则的全部正文内容段落二"));
        UnifiedDocument document = document(elements, pages(1, 2));

        var outcome = marker.mark(document);

        assertEquals(0, outcome.getRepeatPageCount());
        assertEquals(0, outcome.getRepeatSegmentCount());
    }

    @Test
    void wordParagraphRepeatShouldMarkSegmentOnly() {
        String text = "同一段落文本在两个位置重复出现用于重复段判定的测试，长度必须超过五十个字符的最小阈值要求，"
                + "因此这里补充了足够多的内容以确保判定逻辑能够正确命中并完成标记。";
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("a", UnifiedElementType.PARAGRAPH.name(), null, text));
        elements.add(element("b", UnifiedElementType.PARAGRAPH.name(), null, text));
        UnifiedDocument document = document(elements, null);

        var outcome = marker.mark(document);

        assertEquals(0, outcome.getRepeatPageCount());
        assertEquals(1, outcome.getRepeatSegmentCount());
        assertTrue(elements.get(1).getMarks().contains(ElementMark.REPEATED_SEGMENT.name()));
    }

    @Test
    void shortOrHeaderFooterShouldNotMarkSegment() {
        String longText = "页眉页脚文本虽然每页重复，但有独立处置路径，不应进入重复段判定范围。";
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("h1", UnifiedElementType.HEADER.name(), 1, longText));
        elements.add(element("h2", UnifiedElementType.HEADER.name(), 2, longText));
        // 短段落（< 50 字符）重复不标
        elements.add(element("s1", UnifiedElementType.PARAGRAPH.name(), null, "短段落"));
        elements.add(element("s2", UnifiedElementType.PARAGRAPH.name(), null, "短段落"));
        UnifiedDocument document = document(elements, pages(1, 2));

        var outcome = marker.mark(document);

        assertEquals(0, outcome.getRepeatSegmentCount());
        assertNull(elements.get(0).getMarks());
        assertNull(elements.get(2).getMarks());
    }

    @Test
    void blankAndImageOnlyAndGarbledPagesShouldMarkNoise() {
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("t1", UnifiedElementType.PARAGRAPH.name(), 1, "正常页正文内容用于排除噪声判定，文本长度足够且全部为常用字符。"));
        elements.add(element("i3", UnifiedElementType.IMAGE.name(), 3, null));
        // 页 4：乱码页（不可读的替换字符占比 > 0.4）
        elements.add(element("g4", UnifiedElementType.PARAGRAPH.name(), 4, "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD"));
        UnifiedDocument document = document(elements, pages(1, 2, 3, 4));

        var outcome = marker.mark(document);

        assertEquals(3, outcome.getNoisePageCount(), outcome.getWarnings().toString());
        assertTrue(document.getPages().get(1).getMarks().contains(PageMark.NOISE_PAGE.name())); // 页2 空白
        assertTrue(document.getPages().get(2).getMarks().contains(PageMark.NOISE_PAGE.name())); // 页3 纯图片
        assertTrue(document.getPages().get(3).getMarks().contains(PageMark.NOISE_PAGE.name())); // 页4 乱码
        assertNull(document.getPages().get(0).getMarks()); // 页1 不应为噪声
    }

    @Test
    void wordDocumentShouldSkipPageLevel() {
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("a", UnifiedElementType.PARAGRAPH.name(), null, "Word 文档无页概念，页级识别应整体跳过，段落级照常执行。"));
        UnifiedDocument document = document(elements, null);

        var outcome = marker.mark(document);

        assertEquals(0, outcome.getRepeatPageCount());
        assertEquals(0, outcome.getNoisePageCount());
    }

    @Test
    void pageCoveredByContinuationPageRangeShouldNotMarkNoise() {
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("p1", UnifiedElementType.PARAGRAPH.name(), 1, "第一页正文内容用于确认页级判定基线，文本长度足够且全部为常用字符。"));
        // 跨页表：首页在页 2，pageRange 覆盖 2..3（页 3 上只有这张表的续段）
        UnifiedElement table = element("t-2", UnifiedElementType.TABLE.name(), 2, "评分项 评分标准 分值");
        table.setPageRange(List.of(2, 3));
        elements.add(table);
        UnifiedDocument document = document(elements, pages(1, 2, 3));

        var outcome = marker.mark(document);

        assertEquals(0, outcome.getNoisePageCount(), outcome.getWarnings().toString());
        assertNull(document.getPages().get(2).getMarks()); // 页3 由跨页表的 pageRange 覆盖，不判空白
    }
}
