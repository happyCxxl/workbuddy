package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.structure.UnifiedPage;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.structure.PageMark;
import com.workbuddy.common.enums.structure.RelationType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.biz.service.support.stats.StructureStatsSupport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组装统计单测：溯源口径（结构性节点不计入分母、表格经由单元格可回溯）与结论文案。
 */
class StructureStatsSupportTest {

    private UnifiedElement element(String id, UnifiedElementType type) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(type.name());
        return element;
    }

    private UnifiedElement traced(String id, UnifiedElementType type) {
        UnifiedElement element = element(id, type);
        element.setProvenance(new Provenance("f-1", "pdf#page(1)"));
        return element;
    }

    private Map<String, Object> statsOf(List<UnifiedElement> elements) {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(elements);
        return StructureStatsSupport.stats(null, null, document);
    }

    /**
     * 溯源口径：SECTION 不进分母，表格经单元格可回溯。
     *
     * <p>守护 {@code stats}：分母含结构性节点即失败。
     */
    @Test
    void excelSectionsShouldNotLowerCoverage() {
        UnifiedElement section = element("s-1", UnifiedElementType.SECTION);
        UnifiedElement table = element("t-1", UnifiedElementType.TABLE);
        UnifiedElement cell = element("tc-1", UnifiedElementType.TABLE_CELL);
        cell.setProvenance(new Provenance("f-1", "office#sheet[评分表]/cell[0,0]"));
        table.setCells(List.of(cell));

        Map<String, Object> stats = statsOf(List.of(section, table));

        assertEquals(1L, stats.get(StructureStatsSupport.KEY_TRACED_COUNT));
        assertEquals(100, stats.get(StructureStatsSupport.KEY_PROVENANCE_COVERAGE));
    }

    /**
     * 溯源口径：无定位的表格计入分母。
     *
     * <p>守护 {@code stats}：漏计分母即失败。
     */
    @Test
    void tableWithoutProvenanceShouldCountMissing() {
        Map<String, Object> stats = statsOf(List.of(
                traced("n-1", UnifiedElementType.PARAGRAPH), element("t-1", UnifiedElementType.TABLE)));

        assertEquals(1L, stats.get(StructureStatsSupport.KEY_TRACED_COUNT));
        assertEquals(50, stats.get(StructureStatsSupport.KEY_PROVENANCE_COVERAGE));
    }

    /**
     * 结论：覆盖率 83% 不提「偏低」。
     *
     * <p>守护 {@code summary}：阈值判错即失败。
     */
    @Test
    void mostlyTracedDocumentShouldNotBeStatedLow() {
        List<UnifiedElement> elements = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            elements.add(traced("n-" + i, UnifiedElementType.PARAGRAPH));
        }
        elements.add(element("t-1", UnifiedElementType.TABLE));

        Map<String, Object> stats = statsOf(elements);

        assertEquals(5L, stats.get(StructureStatsSupport.KEY_TRACED_COUNT));
        assertEquals(83, stats.get(StructureStatsSupport.KEY_PROVENANCE_COVERAGE));
        assertFalse(StructureStatsSupport.summary(null, stats, "SUCCESS").contains("溯源偏低"));
    }

    /**
     * 结论：覆盖率 0% 提「偏低」。
     *
     * <p>守护 {@code summary}：漏陈述即失败。
     */
    @Test
    void lowCoverageShouldBeStatedInSummary() {
        UnifiedElement table = element("t-1", UnifiedElementType.TABLE);
        table.setCells(List.of(element("tc-1", UnifiedElementType.TABLE_CELL)));

        Map<String, Object> stats = statsOf(List.of(
                element("n-1", UnifiedElementType.PARAGRAPH), table));

        assertEquals(0, stats.get(StructureStatsSupport.KEY_PROVENANCE_COVERAGE));
        assertTrue(StructureStatsSupport.summary(null, stats, "SUCCESS").contains("溯源偏低 0%"));
    }

    /**
     * 接续关系：疑似与确定分别计数并陈述。
     *
     * <p>守护 {@code stats}：疑似漏计即失败。
     */
    @Test
    void suspectedContinuationShouldBeCountedSeparately() {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(List.of(traced("n-1", UnifiedElementType.PARAGRAPH)));
        document.setRelations(List.of(
                new DocumentRelation(RelationType.CONTINUATION_OF.name(), "n-1#p2", "n-1#p1", "续表四条件命中，表头继承"),
                new DocumentRelation(RelationType.CONTINUATION_OF.name(), "n-1#p3", "n-1#p1",
                        "疑似续表（放宽规则），表头继承")));

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(2, stats.get(StructureStatsSupport.KEY_CONTINUATION_COUNT));
        assertEquals(1, stats.get(StructureStatsSupport.KEY_SUSPECTED_CONTINUATION_COUNT));
        assertTrue(StructureStatsSupport.summary(null, stats, "SUCCESS").contains("疑似续表 1 处"));
    }

    /**
     * 标题层级与章节层数：null 层级与 1 级都算一级标题，有 SECTION 时按 SECTION 计章节。
     *
     * <p>守护 {@code stats} 的层级判定：{@code level <= 1} 的边界与 null 兜底写错即失败。
     */
    @Test
    void titleLevelsAndChapterCountShouldFollowBoundaries() {
        UnifiedElement section = element("s-1", UnifiedElementType.SECTION);
        UnifiedElement noLevel = element("t-0", UnifiedElementType.TITLE);
        UnifiedElement level1 = titled("t-1", 1);
        UnifiedElement level2 = titled("t-2", 2);
        UnifiedElement level3 = titled("t-3", 3);
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(new ArrayList<>(List.of(section, noLevel, level1, level2, level3)));

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(4, stats.get(StructureStatsSupport.KEY_TITLE_COUNT));
        assertEquals(1, stats.get(StructureStatsSupport.KEY_CHAPTER_COUNT));
        assertEquals(2, stats.get(StructureStatsSupport.KEY_LEVEL_1));
        assertEquals(1, stats.get(StructureStatsSupport.KEY_LEVEL_2));
        assertEquals(1, stats.get(StructureStatsSupport.KEY_LEVEL_3_PLUS));
    }

    /**
     * 无 SECTION 时章节层数按一级标题计。
     *
     * <p>守护 {@code stats} 的章节口径分支：没有 SECTION 仍报 0 层会让体检显示空树。
     */
    @Test
    void chapterCountShouldFallBackToLevel1Titles() {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(new ArrayList<>(List.of(titled("t-1", 1), titled("t-2", 2))));

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(1, stats.get(StructureStatsSupport.KEY_CHAPTER_COUNT));
    }

    /**
     * 关系计数：父子关系与接续关系分别计数，接续页码取 `#p` 后缀的最小/最大值。
     *
     * <p>守护 {@code stats} 的关系分类与 {@code putContinuationRange}：把父子关系算成接续即失败。
     */
    @Test
    void relationsShouldCountByTypeAndDerivePageRange() {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(new ArrayList<>(List.of(element("n-1", UnifiedElementType.PARAGRAPH))));
        document.setRelations(new ArrayList<>(List.of(
                relation(RelationType.PARENT_CHILD, "n-1#p9", "n-2#p10"),
                relation(RelationType.CONTINUATION_OF, "n-1#p4", "n-2#p6"),
                relation(RelationType.PARENT_CHILD, "n-3", "n-4"))));

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(2, stats.get(StructureStatsSupport.KEY_PARENT_CHILD_COUNT));
        assertEquals(1, stats.get(StructureStatsSupport.KEY_CONTINUATION_COUNT));
        assertEquals(4, stats.get(StructureStatsSupport.KEY_CONTINUATION_FROM_PAGE));
        assertEquals(6, stats.get(StructureStatsSupport.KEY_CONTINUATION_TO_PAGE));
    }

    /**
     * 标记、无坐标元素、页数与 schema 版本逐项计数。
     *
     * <p>守护 {@code countMarks}/{@code countPageMarks}/{@code withoutBboxCount} 的逐项口径。
     */
    @Test
    void marksBboxPagesAndSchemaVersionShouldBeCounted() {
        UnifiedElement repeated = element("n-1", UnifiedElementType.PARAGRAPH);
        repeated.setMarks(new ArrayList<>(List.of(ElementMark.REPEATED_SEGMENT.name())));
        UnifiedElement plain = element("n-2", UnifiedElementType.PARAGRAPH);
        plain.setBbox(new BBox());
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(new ArrayList<>(List.of(repeated, plain)));
        document.setPages(new ArrayList<>(List.of(page(PageMark.REPEATED_PAGE.name()),
                page(PageMark.NOISE_PAGE.name()), page(null))));
        DocumentInfo info = new DocumentInfo();
        info.setSchemaVersion("2.0");
        document.setDocumentInfo(info);

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(1, stats.get(StructureStatsSupport.KEY_REPEATED_SEGMENT_COUNT));
        assertEquals(1, stats.get(StructureStatsSupport.KEY_REPEATED_PAGE_COUNT));
        assertEquals(1, stats.get(StructureStatsSupport.KEY_NOISE_PAGE_COUNT));
        assertEquals(3, stats.get(StructureStatsSupport.KEY_PAGE_COUNT));
        assertEquals(1L, stats.get(StructureStatsSupport.KEY_WITHOUT_BBOX_COUNT));
        assertEquals("2.0", stats.get(StructureStatsSupport.KEY_SCHEMA_VERSION));
    }

    /**
     * 空树与缺失产物：统计为 0 / 返回 null，不写出页码与 schema 版本。
     *
     * <p>守护 {@code stats} 的空集合分支：空树写出 MAX/MIN 页码即失败。
     */
    @Test
    void emptyDocumentShouldNotDerivePageRangeOrSchema() {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(new ArrayList<>());
        document.setRelations(new ArrayList<>(List.of(relation(RelationType.CONTINUATION_OF, "n-1", "n-2"))));

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(0, stats.get(StructureStatsSupport.KEY_ELEMENT_COUNT));
        assertEquals(0, stats.get(StructureStatsSupport.KEY_CHAPTER_COUNT));
        assertEquals(0, stats.get(StructureStatsSupport.KEY_PROVENANCE_COVERAGE));
        assertFalse(stats.containsKey(StructureStatsSupport.KEY_CONTINUATION_FROM_PAGE));
        assertFalse(stats.containsKey(StructureStatsSupport.KEY_SCHEMA_VERSION));
    }

    /**
     * 读产物：对象可读时解析出统一文档，读不到时为 null。
     *
     * <p>守护 {@code readDocument} 的委托：恒返回 null 会让组装详情始终显示「产物不可读」。
     */
    @Test
    void readDocumentShouldParseStoredArtifact() {
        FileStorage fileStorage = Mockito.mock(FileStorage.class);
        ObjectRef ref = new ObjectRef(1L, StorageType.MINIO, "bucket", "structure/unified.json");
        String json = "{\"elements\":[{\"type\":\"TITLE\",\"level\":1}]}";
        Mockito.when(fileStorage.getObject(ref)).thenReturn(json.getBytes(StandardCharsets.UTF_8));

        UnifiedDocument document = StructureStatsSupport.readDocument(fileStorage, ref);

        assertEquals(1, document.getElements().size());
        assertEquals(UnifiedElementType.TITLE.name(), document.getElements().getFirst().getType());
        assertNull(StructureStatsSupport.readDocument(fileStorage, null));
    }

    /**
     * 结论陈述：空树、结构可用（覆盖率正好 80 不提偏低）与失败兜底。
     *
     * <p>守护 {@code structureSummaryBody} 的阈值取等：恰好 80% 被报成偏低即失败。
     */
    @Test
    void summaryShouldStateEmptyHealthyAndFailedShapes() {
        assertEquals("无任何可组装元素（空树）", StructureStatsSupport.summary(null,
                Map.of(StructureStatsSupport.KEY_ELEMENT_COUNT, 0), "SUCCESS"));

        Map<String, Object> exactlyThreshold = Map.of(
                StructureStatsSupport.KEY_ELEMENT_COUNT, 3,
                StructureStatsSupport.KEY_PROVENANCE_COVERAGE, 80,
                StructureStatsSupport.KEY_SUSPECTED_CONTINUATION_COUNT, 0,
                StructureStatsSupport.KEY_REPEATED_SEGMENT_COUNT, 0,
                StructureStatsSupport.KEY_NOISE_PAGE_COUNT, 0);
        assertEquals("结构可用", StructureStatsSupport.summary(null, exactlyThreshold, "SUCCESS"));

        Map<String, Object> noisy = Map.of(
                StructureStatsSupport.KEY_ELEMENT_COUNT, 3,
                StructureStatsSupport.KEY_PROVENANCE_COVERAGE, 100,
                StructureStatsSupport.KEY_SUSPECTED_CONTINUATION_COUNT, 0,
                StructureStatsSupport.KEY_REPEATED_SEGMENT_COUNT, 0,
                StructureStatsSupport.KEY_NOISE_PAGE_COUNT, 2);
        assertEquals("结构可用 · 重复与噪声待处置", StructureStatsSupport.summary(null, noisy, "SUCCESS"));

        assertEquals("解析器超时", StructureStatsSupport.summary("解析器超时", null, "FAILED"));
        assertEquals("组装失败", StructureStatsSupport.summary(" ", null, "FAILED"));
        assertNull(StructureStatsSupport.summary(null, exactlyThreshold, "RUNNING"));
    }

    /**
     * 单页接续：始末页为同一页时同样下发页码；疑似接续单独计数。
     *
     * <p>守护 {@code putContinuationRange} 的 {@code min <= max} 判定与 {@code isSuspectedContinuation}：
     * 写成严格小于会漏掉单页接续，疑似标记判错会漏报。
     */
    @Test
    void statsShouldKeepSinglePageContinuationAndSuspectedCount() {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(new ArrayList<>(List.of(element("e-1", UnifiedElementType.PARAGRAPH))));
        document.setRelations(new ArrayList<>(List.of(
                new DocumentRelation(RelationType.CONTINUATION_OF.name(), "e1#p5", "e2#p5",
                        "疑似续表（放宽规则），表头继承"),
                // 父子关系即使带 #p 页码也不进接续页码范围
                relation(RelationType.PARENT_CHILD, "e3#p9", "e4#p9"))));

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(1, stats.get(StructureStatsSupport.KEY_CONTINUATION_COUNT));
        assertEquals(1, stats.get(StructureStatsSupport.KEY_SUSPECTED_CONTINUATION_COUNT));
        assertEquals(5, stats.get(StructureStatsSupport.KEY_CONTINUATION_FROM_PAGE));
        assertEquals(5, stats.get(StructureStatsSupport.KEY_CONTINUATION_TO_PAGE));
    }

    /** 带层级的标题元素 */
    private UnifiedElement titled(String id, Integer level) {
        UnifiedElement element = element(id, UnifiedElementType.TITLE);
        element.setLevel(level);
        return element;
    }

    /** 关系夹具（detail 可空） */
    private DocumentRelation relation(RelationType type, String from, String to) {
        return new DocumentRelation(type.name(), from, to, null);
    }

    /** 页夹具 */
    private UnifiedPage page(String mark) {
        UnifiedPage page = new UnifiedPage();
        page.setMarks(mark == null ? null : new ArrayList<>(List.of(mark)));
        return page;
    }

    /**
     * 三级及更深标题单独计数：不能被算成二级。
     *
     * <p>守护 {@code stats} 的层级判定（{@code level == 2}）：判定写反会把三级标题计成二级。
     */
    @Test
    void statsShouldCountLevelThreePlusSeparately() {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(new ArrayList<>(List.of(
                titled("t-1", 1), titled("t-2", 2), titled("t-3", 3), titled("t-4", 4))));

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(1, stats.get(StructureStatsSupport.KEY_LEVEL_1));
        assertEquals(1, stats.get(StructureStatsSupport.KEY_LEVEL_2));
        assertEquals(2, stats.get(StructureStatsSupport.KEY_LEVEL_3_PLUS));
    }

    /**
     * 无标记的页不计入任何标记页数。
     *
     * <p>守护 {@code countPageMarks} 的标记判定：条件取反会把"没有标记"的页算成噪声页。
     */
    @Test
    void statsShouldNotCountUnmarkedPages() {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(new ArrayList<>(List.of(element("e-1", UnifiedElementType.PARAGRAPH))));
        document.setPages(new ArrayList<>(List.of(page("NOISE_PAGE"), page(null), page(null))));

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(1, stats.get(StructureStatsSupport.KEY_NOISE_PAGE_COUNT));
    }

    /**
     * 产物为空：统计返回 null（不是空 Map）。
     *
     * <p>守护 {@code stats} 的空产物兜底：返回空 Map 会让详情显示"空树"而不是"不可读"。
     */
    @Test
    void statsShouldReturnNullWhenDocumentMissing() {
        assertNull(StructureStatsSupport.stats(null, null, null));
    }

    /**
     * 统计缺失（产物不可读）：结论文案为 null（不是空串）。
     *
     * <p>守护 {@code structureSummaryBody} 的空统计短路：空串会被界面当成"已陈述但无内容"。
     */
    @Test
    void summaryShouldBeNullWhenStatsMissing() {
        assertNull(StructureStatsSupport.summary(null, null, "SUCCESS"));
    }

    /**
     * 接续关系的页码引用无效（空白 / 缺页码 / 非数字）：不下发页码键。
     *
     * <p>守护 {@code pageOfRelationRef} 的无效引用返回：返回 0 会让页码区间被 0 污染。
     */
    @Test
    void statsShouldOmitRangeWhenContinuationRefsInvalid() {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(new ArrayList<>(List.of(element("e-1", UnifiedElementType.PARAGRAPH))));
        document.setRelations(new ArrayList<>(List.of(
                relation(RelationType.CONTINUATION_OF, " ", "e2#p"),
                relation(RelationType.CONTINUATION_OF, "e3#px", "e4"))));

        Map<String, Object> stats = StructureStatsSupport.stats(null, null, document);

        assertEquals(2, stats.get(StructureStatsSupport.KEY_CONTINUATION_COUNT));
        assertFalse(stats.containsKey(StructureStatsSupport.KEY_CONTINUATION_FROM_PAGE));
        assertFalse(stats.containsKey(StructureStatsSupport.KEY_CONTINUATION_TO_PAGE));
    }
}
