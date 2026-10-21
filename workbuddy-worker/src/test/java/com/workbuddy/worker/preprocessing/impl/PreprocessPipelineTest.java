package com.workbuddy.worker.preprocessing.impl;

import com.workbuddy.worker.preprocessing.impl.rule.EncodingCleanRule;
import com.workbuddy.worker.preprocessing.impl.rule.TextTidyRule;
import com.workbuddy.worker.preprocessing.impl.rule.HeaderFooterRule;
import com.workbuddy.worker.preprocessing.impl.rule.TocRule;
import com.workbuddy.worker.preprocessing.impl.rule.RepeatRule;
import com.workbuddy.worker.preprocessing.impl.rule.FieldNormalizeRule;
import com.workbuddy.worker.preprocessing.impl.rule.NoiseRule;
import com.workbuddy.worker.preprocessing.impl.rule.CustomCleanRule;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.structure.PageMark;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.preprocess.PreprocessOutcome;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.structure.UnifiedPage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.worker.preprocessing.rule.CleanRule;
import com.workbuddy.worker.preprocessing.PreprocessContext;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.common.enums.preprocess.PreprocessAction;
import com.workbuddy.common.enums.preprocess.PreprocessParam;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预处理管线单测：规则顺序与开关（8 规则全量装配，对齐生产 Spring 容器）、BACKUP/IMAGE 口径、规则异常隔离、空文档、确定性。
 */
class PreprocessPipelineTest {

    private PreprocessPipeline pipeline() {
        List<CleanRule> rules = List.of(
                new EncodingCleanRule(), new TextTidyRule(), new HeaderFooterRule(),
                new TocRule(), new RepeatRule(), new FieldNormalizeRule(), new NoiseRule(),
                new CustomCleanRule());
        return new PreprocessPipeline(rules, PropertiesFixtures.preprocess());
    }

    private UnifiedElement element(String id, String type, Integer page, String text) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(type);
        element.setPage(page);
        element.setText(text);
        return element;
    }

    private PreprocessContext context(UnifiedDocument document, PreprocessStrategy strategy) {
        PreprocessContext context = new PreprocessContext();
        context.setDocument(document);
        context.setStrategy(strategy);
        context.setProperties(PropertiesFixtures.preprocess());
        context.setFileResultId(10L);
        context.setUpstreamProductRef(50L);
        return context;
    }

    private UnifiedDocument document(List<UnifiedElement> elements, List<UnifiedPage> pages) {
        UnifiedDocument document = new UnifiedDocument();
        DocumentInfo info = new DocumentInfo();
        info.setDocumentId("doc-10");
        info.setSourceFileRef("F-1");
        document.setDocumentInfo(info);
        document.setElements(elements);
        document.setPages(pages);
        return document;
    }

    private UnifiedPage page(int number, String mark) {
        UnifiedPage page = new UnifiedPage();
        page.setPageId("pg-" + number);
        page.setPageNumber(number);
        if (mark != null) {
            page.setMarks(new ArrayList<>(List.of(mark)));
        }
        return page;
    }

    /**
     * 守护 {@code wordTocRunWindowShouldFollowRunMinLength}：该场景行为改动即失败。
     */
    @Test
    void wordTocRunWindowShouldFollowRunMinLength() {
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("t1", UnifiedElementType.PARAGRAPH.name(), null, "第一章 总则 ...... 1"));
        elements.add(element("t2", UnifiedElementType.PARAGRAPH.name(), null, "第二章 须知 ...... 2"));
        elements.add(element("t3", UnifiedElementType.PARAGRAPH.name(), null, "第三章 评标 ...... 3"));
        elements.add(element("n-1", UnifiedElementType.PARAGRAPH.name(), null, "正文段落"));
        for (UnifiedElement e : List.of(elements.get(0), elements.get(1), elements.get(2))) {
            e.setMarks(new ArrayList<>(List.of(ElementMark.TOC_LINE.name())));
        }
        // Word 无页概念：3 行连续窗口默认（runMinLength=3）判目录，阈值提到 4 就不再判
        PreprocessStrategy strict = StrategyFixtures.preprocessDefaults();
        strict.rule(PreprocessRule.TOC).getParams()
                .put(PreprocessParam.TOC_RUN_MIN_LENGTH.key(), "4");

        PreprocessView strictView = pipeline()
                .preprocess(context(document(elements, new ArrayList<>()), strict)).getView();
        PreprocessView defaultView = pipeline()
                .preprocess(context(document(elements, new ArrayList<>()),
                        StrategyFixtures.preprocessDefaults())).getView();

        assertEquals(ViewElementStatus.NORMAL.name(), strictView.getElements().get(0).getStatus());
        assertEquals(ViewElementStatus.MARKED_TOC.name(), defaultView.getElements().get(0).getStatus());
    }

    /**
     * 守护 {@code fullChainShouldProcessInFixedOrder}：该场景行为改动即失败。
     */
    @Test
    void fullChainShouldProcessInFixedOrder() {
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("h-1", UnifiedElementType.HEADER.name(), 1, "XX项目招标文件"));
        elements.add(element("t1", UnifiedElementType.PARAGRAPH.name(), 1, "第一章 总则 ...... 1"));
        elements.add(element("t2", UnifiedElementType.PARAGRAPH.name(), 1, "第二章 须知 ...... 2"));
        elements.add(element("t3", UnifiedElementType.PARAGRAPH.name(), 1, "第三章 评标 ...... 3"));
        elements.add(element("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                "投标保证金为人民币叁佰万元整（￥3,000,000.00）。"));
        UnifiedElement backup = element("n-2", UnifiedElementType.PARAGRAPH.name(), 1, "被裁决方文本");
        backup.setConflictStatus("BACKUP");
        elements.add(backup);
        elements.add(element("i-1", UnifiedElementType.IMAGE.name(), 1, null));
        for (UnifiedElement e : List.of(elements.get(1), elements.get(2), elements.get(3))) {
            e.setMarks(new ArrayList<>(List.of(ElementMark.TOC_LINE.name())));
        }
        UnifiedDocument document = document(elements, List.of(page(1, null)));

        PreprocessOutcome outcome = pipeline().preprocess(context(document, StrategyFixtures.preprocessDefaults()));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        PreprocessView view = outcome.getView();
        assertEquals("pv-doc-10-preproc-default-v1", view.getViewId());
        assertEquals(10L, view.getFileResultId());
        assertEquals(50L, view.getUpstreamProductRef());
        assertEquals("F-1", view.getSourceFileRef());
        assertEquals(9, outcome.getStepLogs().size());

        ViewElement header = view.getElements().getFirst();
        assertEquals(ViewElementStatus.MARKED_HEADER.name(), header.getStatus());
        assertEquals("XX项目招标文件", header.getRawText());

        ViewElement toc = view.getElements().get(1);
        assertEquals(ViewElementStatus.MARKED_TOC.name(), toc.getStatus());

        ViewElement amount = view.getElements().get(4);
        assertEquals(ViewElementStatus.NORMAL.name(), amount.getStatus());
        assertTrue(amount.getNormalizedText().contains("叁佰万元整"));
        assertTrue(amount.getNormalizedFields().stream().anyMatch(f -> "3000000.00".equals(f.getValue())));

        ViewElement backupView = view.getElements().get(5);
        assertEquals(ViewElementStatus.BACKUP_SKIPPED.name(), backupView.getStatus());
        assertNull(backupView.getNormalizedText());

        assertEquals(ViewElementStatus.IMAGE_REF_ONLY.name(), view.getElements().get(6).getStatus());
    }

    /**
     * 守护 {@code tableCellShouldCarryPositionIntoView}：该场景行为改动即失败。
     */
    @Test
    void tableCellShouldCarryPositionIntoView() {
        List<UnifiedElement> elements = new ArrayList<>();
        UnifiedElement table = new UnifiedElement();
        table.setId("t-1");
        table.setType(UnifiedElementType.TABLE.name());
        table.setPage(1);
        table.setRows(2);
        table.setCols(3);
        List<UnifiedElement> cells = new ArrayList<>();
        UnifiedElement c00 = element("tc-0-0", UnifiedElementType.TABLE_CELL.name(), 1, "序号");
        c00.setRow(0);
        c00.setCol(0);
        c00.setIsHeader(true);
        cells.add(c00);
        UnifiedElement c12 = element("tc-1-2", UnifiedElementType.TABLE_CELL.name(), 1, "技术方案");
        c12.setRow(1);
        c12.setCol(2);
        c12.setIsHeader(false);
        cells.add(c12);
        table.setCells(cells);
        elements.add(table);
        UnifiedDocument document = document(elements, null);

        PreprocessOutcome outcome = pipeline().preprocess(context(document, StrategyFixtures.preprocessDefaults()));

        ViewElement viewTable = outcome.getView().getElements().getFirst();
        assertEquals(2, viewTable.getCells().size());
        assertEquals(0, viewTable.getCells().getFirst().getRow());
        assertEquals(0, viewTable.getCells().get(0).getCol());
        assertEquals(Boolean.TRUE, viewTable.getCells().get(0).getIsHeader());
        assertEquals(1, viewTable.getCells().get(1).getRow());
        assertEquals(2, viewTable.getCells().get(1).getCol());
        assertEquals(Boolean.FALSE, viewTable.getCells().get(1).getIsHeader());
    }

    /**
     * 守护 {@code strategySwitchesShouldExclude}：该场景行为改动即失败。
     */
    @Test
    void strategySwitchesShouldExclude() {
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("h-1", UnifiedElementType.HEADER.name(), 1, "XX项目招标文件"));
        for (int i = 1; i <= 3; i++) {
            UnifiedElement toc = element("t" + i, UnifiedElementType.PARAGRAPH.name(), 1,
                    "第" + i + "章 内容 ...... " + i);
            toc.setMarks(new ArrayList<>(List.of(ElementMark.TOC_LINE.name())));
            elements.add(toc);
        }
        UnifiedElement noise = element("n-2", UnifiedElementType.PARAGRAPH.name(), 2, "噪声内容");
        elements.add(noise);
        UnifiedDocument document = document(elements, List.of(
                page(1, null), page(2, PageMark.NOISE_PAGE.name())));

        PreprocessStrategy strategy = StrategyFixtures.preprocessDefaults();
        strategy.rule(PreprocessRule.HEADER_FOOTER).setAction(PreprocessAction.EXCLUDE.name());
        strategy.rule(PreprocessRule.TOC).setAction(PreprocessAction.EXCLUDE.name());
        strategy.rule(PreprocessRule.NOISE).setAction(PreprocessAction.EXCLUDE.name());

        PreprocessOutcome outcome = pipeline().preprocess(context(document, strategy));

        PreprocessView view = outcome.getView();
        assertEquals(ViewElementStatus.EXCLUDED_HEADER.name(), view.getElements().get(0).getStatus());
        assertNull(view.getElements().get(0).getNormalizedText());
        assertEquals(ViewElementStatus.EXCLUDED_TOC.name(), view.getElements().get(1).getStatus());
        assertEquals(ViewElementStatus.EXCLUDED_NOISE.name(), view.getElements().get(4).getStatus());
        assertNull(view.getElements().get(4).getNormalizedText());
    }

    /**
     * 守护 {@code repeatedMarkShouldKeepOnlyFirstCopy}：该场景行为改动即失败。
     */
    @Test
    void repeatedMarkShouldKeepOnlyFirstCopy() {
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("n-1", UnifiedElementType.PARAGRAPH.name(), null, "首份内容"));
        UnifiedElement repeated = element("n-2", UnifiedElementType.PARAGRAPH.name(), null, "重复份内容");
        repeated.setMarks(new ArrayList<>(List.of(ElementMark.REPEATED_SEGMENT.name())));
        elements.add(repeated);
        UnifiedDocument document = document(elements, null);

        PreprocessOutcome outcome = pipeline().preprocess(context(document, StrategyFixtures.preprocessDefaults()));

        assertEquals(ViewElementStatus.NORMAL.name(), outcome.getView().getElements().get(0).getStatus());
        assertNotNull(outcome.getView().getElements().get(0).getNormalizedText());
        assertEquals(ViewElementStatus.REPEATED.name(), outcome.getView().getElements().get(1).getStatus());
        assertNull(outcome.getView().getElements().get(1).getNormalizedText());
    }

    /**
     * 守护 {@code ruleExceptionShouldIsolateToPartialSuccess}：该场景行为改动即失败。
     */
    @Test
    void ruleExceptionShouldIsolateToPartialSuccess() {
        List<CleanRule> rules = createRules();
        PreprocessPipeline pipeline = new PreprocessPipeline(rules, PropertiesFixtures.preprocess());
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("n-1", UnifiedElementType.PARAGRAPH.name(), null, "正文内容"));
        UnifiedDocument document = document(elements, null);

        PreprocessOutcome outcome = pipeline.preprocess(context(document, StrategyFixtures.preprocessDefaults()));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertTrue(outcome.getWarnings().stream().anyMatch(w -> w.contains("异常规则")));
        // 视图照产（该规则产物缺失不影响其他层）
        assertNotNull(outcome.getView());
        assertEquals(1, outcome.getView().getElements().size());
    }

    @NotNull
    private static List<CleanRule> createRules() {
        List<CleanRule> rules = new ArrayList<>(List.of(
                new EncodingCleanRule(), new TextTidyRule(), new HeaderFooterRule(),
                new TocRule(), new RepeatRule(), new FieldNormalizeRule(), new NoiseRule()));
        // 注入一条必抛异常的规则（模拟新规则缺陷：只影响该规则产物）
        rules.add(new CleanRule() {
            @Override
            public String name() {
                return "broken-rule-v1";
            }

            @Override
            public String stepName() {
                return "异常规则";
            }

            @Override
            public int order() {
                return 8;
            }

            @Override
            public boolean enabledIn(PreprocessStrategy strategy) {
                return true;
            }

            @Override
            public RuleOutcome apply(ViewElement element, RuleContext context) {
                throw new IllegalStateException("boom");
            }
        });
        return rules;
    }

    /**
     * 守护 {@code emptyDocumentShouldFailWithPreprocessEmpty}：该场景行为改动即失败。
     */
    @Test
    void emptyDocumentShouldFailWithPreprocessEmpty() {
        UnifiedDocument document = document(new ArrayList<>(), null);

        PreprocessOutcome outcome = pipeline().preprocess(context(document, StrategyFixtures.preprocessDefaults()));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.PREPROCESS_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code missingStrategyShouldFailWithPreprocessFailed}：该场景行为改动即失败。
     */
    @Test
    void missingStrategyShouldFailWithPreprocessFailed() {
        // 上下文无策略（快照缺失或损坏由 Runner 拦截）：管线自身同样拒绝，不构造默认策略
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("n-1", UnifiedElementType.PARAGRAPH.name(), null, "正文内容"));
        UnifiedDocument document = document(elements, null);

        PreprocessOutcome outcome = pipeline().preprocess(context(document, null));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.PREPROCESS_FAILED.name(), outcome.getErrorCode());
        assertEquals("任务未带预处理策略快照，无法预处理", outcome.getErrorMsg());
        assertNull(outcome.getView());
    }

    /**
     * 守护 {@code sameInputSameStrategyShouldBeDeterministic}：该场景行为改动即失败。
     */
    @Test
    void sameInputSameStrategyShouldBeDeterministic() {
        List<UnifiedElement> elements = new ArrayList<>();
        elements.add(element("n-1", UnifiedElementType.PARAGRAPH.name(), null,
                "投标保证金为人民币叁佰万元整（￥3,000,000.00）。"));
        UnifiedDocument document = document(elements, null);

        PreprocessOutcome first = pipeline().preprocess(context(document, StrategyFixtures.preprocessDefaults()));
        PreprocessOutcome second = pipeline().preprocess(context(document, StrategyFixtures.preprocessDefaults()));

        assertEquals(JsonUtilLike.toJson(first), JsonUtilLike.toJson(second));
    }

    /** 简单 JSON 序列化（避免测试依赖）：使用对象字段级一致性校验 */
    private static final class JsonUtilLike {
        static String toJson(PreprocessOutcome outcome) {
            return outcome.getView().getViewId() + "|"
                    + outcome.getView().getElements().stream()
                    .map(e -> e.getElementId() + ":" + e.getStatus() + ":" + e.getNormalizedText())
                    .reduce("", String::concat) + "|" + outcome.getSuggestedStatus().name();
        }
    }
}
