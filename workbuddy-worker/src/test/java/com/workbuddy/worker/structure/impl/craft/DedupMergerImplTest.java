package com.workbuddy.worker.structure.impl.craft;
import com.workbuddy.common.enums.structure.UnifiedElementType;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.StructureProperties;
import com.workbuddy.worker.structure.craft.MergeOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 去重合并单测：IoU+文本相似双判定 / 原生优先 / 同框不同内容冲突 PRIMARY-BACKUP。
 *
 * @author cxxl
 */
class DedupMergerImplTest {

    private DedupMergerImpl merger;
    private StructureProperties properties;

    @BeforeEach
    void setUp() {
        properties = new StructureProperties();
        merger = new DedupMergerImpl();
    }

    private AssembleContext context() {
        AssembleContext context = new AssembleContext();
        context.setProperties(properties);
        return context;
    }

    private UnifiedElement element(String id, String text, BBox bbox, String source) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(UnifiedElementType.PARAGRAPH.name());
        element.setText(text);
        element.setBbox(bbox);
        element.setExtension(Map.of("source", source));
        return element;
    }

    @Test
    void overlapSameTextShouldMergeNativeFirst() {
        BBox box = new BBox(72, 100, 300, 20);
        UnifiedElement nativeEl = element("n-1", "投标保证金为人民币叁佰万元整。", box, "native");
        UnifiedElement ocrEl = element("o-1", "投标保证金为人民币叁佰万元整。", new BBox(73, 101, 298, 19), "ocr");

        MergeOutcome outcome = merger.merge(List.of(nativeEl, ocrEl), context());

        assertEquals(1, outcome.getElements().size());
        assertEquals("n-1", outcome.getElements().getFirst().getId());
        assertEquals(1, outcome.getMergePairs());
        assertNull(nativeEl.getConflictStatus());
    }

    @Test
    void overlapDifferentTextShouldConflictWithPrimaryBackup() {
        BBox box = new BBox(72, 100, 300, 20);
        UnifiedElement nativeEl = element("n-1", "投标保证金为人民币叁佰万元整。", box, "native");
        UnifiedElement ocrEl = element("o-1", "担保函编号：GH-2024-8888", new BBox(73, 101, 298, 19), "ocr");

        MergeOutcome outcome = merger.merge(List.of(nativeEl, ocrEl), context());

        assertEquals(2, outcome.getElements().size());
        assertEquals(1, outcome.getConflicts().size());
        assertEquals("PRIMARY", nativeEl.getConflictStatus());
        assertEquals("BACKUP", ocrEl.getConflictStatus());
    }

    @Test
    void sameSourceShouldNotMerge() {
        BBox box = new BBox(72, 100, 300, 20);
        UnifiedElement a = element("n-1", "段落一内容", box, "native");
        UnifiedElement b = element("n-2", "段落一内容", new BBox(73, 101, 298, 19), "native");

        MergeOutcome outcome = merger.merge(List.of(a, b), context());

        assertEquals(2, outcome.getElements().size());
        assertEquals(0, outcome.getMergePairs());
    }

    @Test
    void laterHigherPrioritySourceShouldWinOverEarlierLowerOne() {
        BBox box = new BBox(72, 100, 300, 20);
        // 低优先级（ocr）先到、高优先级（native）后到：取舍只看优先级，与到达顺序无关
        UnifiedElement ocrEl = element("o-1", "投标保证金为人民币叁佰万元整。", box, "ocr");
        UnifiedElement nativeEl = element("n-1", "投标保证金为人民币叁佰万元整。",
                new BBox(73, 101, 298, 19), "native");

        MergeOutcome outcome = merger.merge(List.of(ocrEl, nativeEl), context());

        assertEquals(1, outcome.getElements().size());
        assertEquals("n-1", outcome.getElements().getFirst().getId());
        // 后到者取胜的路径同样计入合并对数（计数在取舍分支之前）
        assertEquals(1, outcome.getMergePairs());
    }

    @Test
    void unknownSourceShouldRankLast() {
        BBox box = new BBox(72, 100, 300, 20);
        UnifiedElement unknown = element("x-1", "投标保证金为人民币叁佰万元整。", box, "mystery-route");
        UnifiedElement nativeEl = element("n-1", "投标保证金为人民币叁佰万元整。",
                new BBox(73, 101, 298, 19), "native");

        MergeOutcome outcome = merger.merge(List.of(unknown, nativeEl), context());

        assertEquals(1, outcome.getElements().size());
        assertEquals("n-1", outcome.getElements().getFirst().getId());
    }

    @Test
    void sameRouteDifferentProviderShouldNotBeCrossSource() {
        BBox box = new BBox(72, 100, 300, 20);
        UnifiedElement a = elementWithProvider("n-1", "段落一内容", box, "native", "pdfbox-3.0.4");
        UnifiedElement b = elementWithProvider("n-2", "段落一内容", new BBox(73, 101, 298, 19),
                "native", "pdfbox-2.0.0");

        MergeOutcome outcome = merger.merge(List.of(a, b), context());

        assertEquals(2, outcome.getElements().size());
        assertEquals(0, outcome.getMergePairs());
    }

    private UnifiedElement elementWithProvider(String id, String text, BBox bbox, String source, String provider) {
        UnifiedElement element = element(id, text, bbox, source);
        element.setExtension(Map.of("source", source, "provider", provider));
        return element;
    }
}
