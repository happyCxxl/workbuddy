package com.workbuddy.worker.structure.impl.craft;
import com.workbuddy.common.enums.structure.UnifiedElementType;

import com.workbuddy.common.domain.structure.ContinuationJudgeContext;
import com.workbuddy.common.domain.structure.ContinuationJudgeResult;
import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.TitleJudgeContext;
import com.workbuddy.common.domain.structure.TitleJudgeResult;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.parse.FontInfo;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.StructureProperties;
import com.workbuddy.worker.structure.craft.TreeOutcome;
import com.workbuddy.worker.structure.impl.StructureJudgeRegistry;
import com.workbuddy.worker.structure.judge.StructureJudgeProvider;
import com.workbuddy.worker.structure.impl.title.ChapterTitleRule;
import com.workbuddy.worker.structure.impl.title.CnDotTitleRule;
import com.workbuddy.worker.structure.impl.title.CnParenTitleRule;
import com.workbuddy.worker.structure.impl.title.FontSignalTitleRule;
import com.workbuddy.worker.structure.impl.title.NumberTitleRule;
import com.workbuddy.worker.structure.impl.title.SingleNumberTitleRule;
import com.workbuddy.worker.structure.impl.title.StyleTitleRule;
import com.workbuddy.worker.structure.title.TitleRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构组装单测：标题判定规则链（六规则真实实例，样式/编号模式/字号加粗/候选告警）+ Excel sheet→SECTION + 章节树。
 */
class StructureAssemblerImplTest {

    private StructureAssemblerImpl assembler;
    private StructureProperties properties;

    @BeforeEach
    void setUp() {
        properties = new StructureProperties();
        List<TitleRule> rules = List.of(new StyleTitleRule(), new ChapterTitleRule(),
                new SingleNumberTitleRule(), new NumberTitleRule(), new CnParenTitleRule(),
                new CnDotTitleRule(), new FontSignalTitleRule());
        assembler = new StructureAssemblerImpl(
                new StructureJudgeRegistry(new ArrayList<>(), properties), rules);
    }

    private AssembleContext context(String mimeType) {
        AssembleContext context = new AssembleContext();
        context.setFileResultId(1L);
        context.setSourceFileType(mimeType);
        context.setProperties(properties);
        return context;
    }

    private UnifiedElement paragraph(String text, Double size, Boolean bold) {
        UnifiedElement element = new UnifiedElement();
        element.setId("n-" + Math.abs(text.hashCode()));
        element.setType(UnifiedElementType.PARAGRAPH.name());
        element.setText(text);
        element.setFont(new FontInfo("SimSun", size, bold));
        return element;
    }

    @Test
    void styleTitleShouldGetLevelFromHeading() {
        UnifiedElement styled = paragraph("第一章 投标人须知", 12d, false);
        styled.setExtension(Map.of("style", "Heading1"));

        assembler.assembleTree(List.of(styled), context("application/pdf"));

        assertEquals(UnifiedElementType.TITLE.name(), styled.getType());
        assertEquals(1, styled.getLevel());
        assertEquals("style", styled.getTitleEvidence().getCascade());
    }

    @Test
    void chapterPatternShouldBeLevelOneTitle() {
        UnifiedElement chapter = paragraph("第三章 技术要求", 14d, true);

        assembler.assembleTree(List.of(chapter), context("application/pdf"));

        assertEquals(UnifiedElementType.TITLE.name(), chapter.getType());
        assertEquals(1, chapter.getLevel());
        assertEquals("number-pattern", chapter.getTitleEvidence().getCascade());
        assertEquals("第X章", chapter.getTitleEvidence().getPattern());
    }

    @Test
    void deepNumberPatternShouldGetLevelByDots() {
        UnifiedElement deep = paragraph("1.2.3.4.1 某某条款", 12d, true);

        assembler.assembleTree(List.of(deep), context("application/pdf"));

        assertEquals(UnifiedElementType.TITLE.name(), deep.getType());
        assertEquals(5, deep.getLevel());
        assertEquals("number-pattern", deep.getTitleEvidence().getCascade());
    }

    @Test
    void numberPatternWithoutFontSignalShouldBeCandidate() {
        UnifiedElement suspicious = paragraph("3.14 是 π", 12d, false);

        TreeOutcome outcome = assembler.assembleTree(List.of(suspicious), context("application/pdf"));

        assertEquals(UnifiedElementType.PARAGRAPH.name(), suspicious.getType());
        assertEquals(1, outcome.getTitleCandidateCount());
        assertTrue(outcome.getTitleCountByCascade().isEmpty());
    }

    @Test
    void boldLargeFontShouldBeTitleByFontSignal() {
        UnifiedElement boldLarge = paragraph("总体要求", 16d, true);
        UnifiedElement body = paragraph("本须知适用于参与本次采购活动的所有供应商。", 10d, false);
        UnifiedElement body2 = paragraph("投标保证金为人民币叁佰万元整。", 10d, false);

        TreeOutcome outcome = assembler.assembleTree(List.of(boldLarge, body, body2), context("application/pdf"));

        assertEquals(UnifiedElementType.TITLE.name(), boldLarge.getType());
        assertEquals("font-signal", boldLarge.getTitleEvidence().getCascade());
        assertEquals(1, outcome.getTitleCount());
    }

    /** 假模型判定实现：候选文本含「重大」才判为标题，用于走通兜底分支 */
    private static final class FakeJudge implements StructureJudgeProvider {

        @Override
        public TitleJudgeResult judgeTitle(TitleJudgeContext judgeContext) {
            TitleJudgeResult result = new TitleJudgeResult();
            result.setIsTitle(judgeContext.getCandidateText() != null
                    && judgeContext.getCandidateText().contains("重大"));
            result.setLevel(2);
            result.setConfidence(0.87d);
            result.setModel("fake-judge");
            return result;
        }

        @Override
        public ContinuationJudgeResult judgeContinuation(ContinuationJudgeContext judgeContext) {
            return new ContinuationJudgeResult();
        }
    }

    private StructureAssemblerImpl assemblerWithJudge(boolean enabled) {
        properties.setModelFallbackEnabled(enabled);
        List<TitleRule> rules = List.of(new StyleTitleRule(), new ChapterTitleRule(),
                new SingleNumberTitleRule(), new NumberTitleRule(), new CnParenTitleRule(),
                new CnDotTitleRule(), new FontSignalTitleRule());
        return new StructureAssemblerImpl(
                new StructureJudgeRegistry(List.of(new FakeJudge()), properties), rules);
    }

    @Test
    void modelFallbackShouldTitleWhenRulesMiss() {
        StructureAssemblerImpl judgeAssembler = assemblerWithJudge(true);
        // 无样式、无编号、字号未达 1.15 倍且不加粗 → 规则全 miss，交给模型兜底
        UnifiedElement candidate = paragraph("重大事项说明", 11d, false);

        judgeAssembler.assembleTree(List.of(candidate), context("application/pdf"));

        assertEquals(UnifiedElementType.TITLE.name(), candidate.getType());
        assertEquals(2, candidate.getLevel());
        assertEquals("model", candidate.getTitleEvidence().getCascade());
        assertEquals("fake-judge conf=0.87", candidate.getTitleEvidence().getModelEvidence());
    }

    @Test
    void modelFallbackShouldStayOffWhenDisabled() {
        StructureAssemblerImpl judgeAssembler = assemblerWithJudge(false);
        UnifiedElement candidate = paragraph("重大事项说明", 11d, false);

        judgeAssembler.assembleTree(List.of(candidate), context("application/pdf"));

        assertEquals(UnifiedElementType.PARAGRAPH.name(), candidate.getType());
    }

    @Test
    void headerFooterShouldStayOutOfChapterTreeAndOrderChain() {
        UnifiedElement header = new UnifiedElement();
        header.setId("h-1");
        header.setType(UnifiedElementType.HEADER.name());
        header.setText("某某公司投标文件");
        UnifiedElement footer = new UnifiedElement();
        footer.setId("f-1");
        footer.setType(UnifiedElementType.FOOTER.name());
        footer.setText("第 1 页");
        UnifiedElement title = paragraph("第一章 总则", 14d, true);
        title.setId("t-1");
        UnifiedElement content = paragraph("正文内容一段。", 10d, false);
        content.setId("c-1");
        AssembleContext ctx = context("application/pdf");

        TreeOutcome outcome = assembler.assembleTree(
                new ArrayList<>(List.of(header, title, content, footer)), ctx);
        List<DocumentRelation> relations = assembler.buildRelations(outcome.getElements(), ctx);

        // 页眉页脚留在元素表里，但不挂章节树
        assertEquals(4, outcome.getElements().size());
        assertTrue(relations.stream()
                .filter(r -> "PARENT_CHILD".equals(r.getType()))
                .noneMatch(r -> "h-1".equals(r.getTo()) || "f-1".equals(r.getTo())));
        // 也不进正文流：阅读顺序链只有 标题 → 正文
        List<String> chain = relations.stream()
                .filter(r -> "NEXT".equals(r.getType()))
                .map(r -> r.getFrom() + "->" + r.getTo())
                .toList();
        assertEquals(List.of("t-1->c-1"), chain);
    }

    @Test
    void chapterTreeShouldAttachContentToNearestTitle() {
        UnifiedElement title = paragraph("第一章 总则", 14d, true);
        UnifiedElement content = paragraph("投标保证金为人民币叁佰万元整。", 10d, false);
        AssembleContext ctx = context("application/pdf");

        TreeOutcome outcome = assembler.assembleTree(List.of(title, content), ctx);
        List<DocumentRelation> relations = assembler.buildRelations(outcome.getElements(), ctx);

        List<DocumentRelation> parentChild = relations.stream()
                .filter(r -> "PARENT_CHILD".equals(r.getType()))
                .toList();
        assertEquals(1, parentChild.size());
        assertEquals(title.getId(), parentChild.getFirst().getFrom());
        assertEquals(content.getId(), parentChild.getFirst().getTo());
        // 阅读顺序关系
        assertTrue(relations.stream().anyMatch(r -> "NEXT".equals(r.getType())));
    }

    @Test
    void excelShouldBuildSheetSections() {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-1");
        table.setType(UnifiedElementType.TABLE.name());
        table.setText("评分表");
        table.setExtension(Map.of("sheetName", "评分表"));
        AssembleContext ctx = context("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        TreeOutcome outcome = assembler.assembleTree(new ArrayList<>(List.of(table)), ctx);
        List<DocumentRelation> relations = assembler.buildRelations(outcome.getElements(), ctx);

        assertEquals(2, outcome.getElements().size());
        UnifiedElement section = outcome.getElements().getFirst();
        assertEquals(UnifiedElementType.SECTION.name(), section.getType());
        assertEquals("评分表", section.getText());
        assertTrue(relations.stream().anyMatch(r ->
                "PARENT_CHILD".equals(r.getType()) && section.getId().equals(r.getFrom())
                        && "t-1".equals(r.getTo())));
    }

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private UnifiedElement sheetTable(String id, String sheetName) {
        UnifiedElement table = new UnifiedElement();
        table.setId(id);
        table.setType(UnifiedElementType.TABLE.name());
        table.setText(sheetName + " 数据");
        table.setExtension(Map.of("sheetName", sheetName));
        return table;
    }

    @Test
    void excelSectionsShouldInterleaveWithTheirSheets() {
        TreeOutcome outcome = assembler.assembleTree(
                new ArrayList<>(List.of(sheetTable("t-1", "评分表"), sheetTable("t-2", "报价表"))),
                context(XLSX_MIME));

        List<String> order = outcome.getElements().stream().map(UnifiedElement::getText).toList();
        assertEquals(List.of("评分表", "评分表 数据", "报价表", "报价表 数据"), order);
    }

    @Test
    void excelSectionsShouldBeSiblingsAndOwnTheirTables() {
        AssembleContext ctx = context(XLSX_MIME);
        TreeOutcome outcome = assembler.assembleTree(
                new ArrayList<>(List.of(sheetTable("t-1", "评分表"), sheetTable("t-2", "报价表"))), ctx);
        List<DocumentRelation> relations = assembler.buildRelations(outcome.getElements(), ctx);

        List<UnifiedElement> elements = outcome.getElements();
        List<DocumentRelation> parentChild = relations.stream()
                .filter(r -> "PARENT_CHILD".equals(r.getType()))
                .toList();
        assertEquals(2, parentChild.size());
        assertEquals(Set.of(elements.get(0).getId(), elements.get(2).getId()),
                parentChild.stream().map(DocumentRelation::getFrom).collect(Collectors.toSet()));
        assertEquals(Set.of("t-1", "t-2"),
                parentChild.stream().map(DocumentRelation::getTo).collect(Collectors.toSet()));
    }

    @Test
    void excelNextChainShouldFollowInterleavedOrder() {
        AssembleContext ctx = context(XLSX_MIME);
        TreeOutcome outcome = assembler.assembleTree(
                new ArrayList<>(List.of(sheetTable("t-1", "评分表"), sheetTable("t-2", "报价表"))), ctx);
        List<DocumentRelation> relations = assembler.buildRelations(outcome.getElements(), ctx);

        List<UnifiedElement> elements = outcome.getElements();
        List<String> expected = List.of(
                elements.get(0).getId() + "->" + elements.get(1).getId(),
                elements.get(1).getId() + "->" + elements.get(2).getId(),
                elements.get(2).getId() + "->" + elements.get(3).getId());
        List<String> chain = relations.stream()
                .filter(r -> "NEXT".equals(r.getType()))
                .map(r -> r.getFrom() + "->" + r.getTo())
                .toList();
        assertEquals(expected, chain);
    }

    @Test
    void styleRuleShouldRejectStyleNameContainingHeading() {
        UnifiedElement styled = paragraph("投标保证金为人民币叁佰万元整。", 10d, false);
        styled.setExtension(Map.of("style", "MyHeading2Style"));

        assembler.assembleTree(List.of(styled), context("application/pdf"));

        assertEquals(UnifiedElementType.PARAGRAPH.name(), styled.getType());
    }

    @Test
    void styleRuleShouldClampDeepLevel() {
        UnifiedElement styled = paragraph("第某章 标题", 12d, false);
        styled.setExtension(Map.of("style", "Heading12"));

        assembler.assembleTree(List.of(styled), context("application/pdf"));

        assertEquals(UnifiedElementType.TITLE.name(), styled.getType());
        assertEquals(9, styled.getLevel());
    }

    @Test
    void styleRuleShouldRequireShortText() {
        UnifiedElement styled = paragraph("这是一段被套用了标题样式的长正文内容，用于确认样式规则同样要求短句约束，"
                + "避免整段正文被判定为标题而撑坏章节结构。", 12d, false);
        styled.setExtension(Map.of("style", "Heading2"));

        assembler.assembleTree(List.of(styled), context("application/pdf"));

        assertEquals(UnifiedElementType.PARAGRAPH.name(), styled.getType());
    }

    @Test
    void singleNumberHeadingShouldBeLevelOne() {
        UnifiedElement heading = paragraph("1 总则", 14d, true);

        assembler.assembleTree(List.of(heading), context("application/pdf"));

        assertEquals(UnifiedElementType.TITLE.name(), heading.getType());
        assertEquals(1, heading.getLevel());
        assertEquals("single-number-pattern", heading.getTitleEvidence().getCascade());
        assertEquals("1", heading.getTitleEvidence().getPattern());
    }

    @Test
    void dotNumberHeadingShouldStayLevelTwo() {
        UnifiedElement dotted = paragraph("1.1 范围", 14d, true);

        assembler.assembleTree(List.of(dotted), context("application/pdf"));

        assertEquals(UnifiedElementType.TITLE.name(), dotted.getType());
        assertEquals(2, dotted.getLevel());
        assertEquals("number-pattern", dotted.getTitleEvidence().getCascade());
        assertEquals("1.1", dotted.getTitleEvidence().getPattern());
    }

    @Test
    void decimalParagraphShouldNotBecomeTitle() {
        UnifiedElement decimal = paragraph("3.14 是圆周率的近似值，属于正文说明。", 10d, false);

        TreeOutcome outcome = assembler.assembleTree(List.of(decimal), context("application/pdf"));

        assertEquals(UnifiedElementType.PARAGRAPH.name(), decimal.getType());
        assertEquals(1, outcome.getTitleCandidateCount());
    }

    @Test
    void singleNumberBodyLineShouldBeCandidateOnly() {
        UnifiedElement body = paragraph("2 个工作日", 10d, false);

        TreeOutcome outcome = assembler.assembleTree(List.of(body), context("application/pdf"));

        assertEquals(UnifiedElementType.PARAGRAPH.name(), body.getType());
        assertEquals(1, outcome.getTitleCandidateCount());
    }
}
