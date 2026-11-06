package com.workbuddy.worker.structure.impl;

import cn.hutool.json.JSONUtil;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.StructureProperties;
import com.workbuddy.worker.structure.impl.craft.DedupMergerImpl;
import com.workbuddy.worker.structure.impl.craft.NormalizerImpl;
import com.workbuddy.worker.structure.impl.craft.ReadingOrderResolverImpl;
import com.workbuddy.worker.structure.impl.craft.RepeatNoiseMarkerImpl;
import com.workbuddy.worker.structure.impl.craft.StructureAssemblerImpl;
import com.workbuddy.worker.structure.impl.craft.TableContinuationResolverImpl;
import com.workbuddy.worker.structure.impl.title.ChapterTitleRule;
import com.workbuddy.worker.structure.impl.title.CnDotTitleRule;
import com.workbuddy.worker.structure.impl.title.CnParenTitleRule;
import com.workbuddy.worker.structure.impl.title.FontSignalTitleRule;
import com.workbuddy.worker.structure.impl.title.NumberTitleRule;
import com.workbuddy.worker.structure.impl.title.SingleNumberTitleRule;
import com.workbuddy.worker.structure.impl.title.StyleTitleRule;
import com.workbuddy.worker.structure.title.TitleRule;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 结构环节真实产物回归：混合 PDF 的解析产物（native 数字页 + ocr 扫描页，含无文本的 IMAGE 元素）
 * 经 hutool 反序列化后组装不抛异常——守护 {@code DedupMergerImpl} 对 null 文本的容错（IMAGE 元素无 text）。
 */
class StructureRealProductAssemblyTest {

    /** 守护 {@code DedupMergerImpl}：真实产物含无 text 的 IMAGE 元素时组装不抛异常。 */
    @Test
    void realProductAssemblyShouldNotThrow() throws Exception {
        String json = new String(getClass().getClassLoader()
                .getResourceAsStream("parse-product-real.json").readAllBytes(), StandardCharsets.UTF_8);
        ParseResult result = JsonUtil.toObject(json, ParseResult.class);

        StructureProperties properties = PropertiesFixtures.structure();
        StructureJudgeRegistry registry = new StructureJudgeRegistry(new ArrayList<>(), properties);
        List<TitleRule> rules = List.of(new StyleTitleRule(), new ChapterTitleRule(),
                new SingleNumberTitleRule(), new NumberTitleRule(), new CnParenTitleRule(),
                new CnDotTitleRule(), new FontSignalTitleRule());
        AssemblerPipeline pipeline = new AssemblerPipeline(new NormalizerImpl(), new DedupMergerImpl(),
                new ReadingOrderResolverImpl(), new StructureAssemblerImpl(registry, rules),
                new TableContinuationResolverImpl(registry), new RepeatNoiseMarkerImpl(properties));

        AssembleContext context = new AssembleContext();
        context.setTaskId(1L);
        context.setFileResultId(2L);
        context.setProperties(properties);

        var outcome = pipeline.assemble(result, context);

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(4, outcome.getDocument().getElements().size());
        assertGoldenSnapshot(JSONUtil.toJsonPrettyStr(outcome.getDocument()));
    }

    /** 真实产物组装结果与黄金快照逐行一致；{@code GOLDEN_UPDATE=true} 时重建基线。 */
    private void assertGoldenSnapshot(String actual) throws Exception {
        Path golden = Path.of("src", "test", "resources", "structure-golden", "parse-product-real.json");
        Files.createDirectories(golden.getParent());
        if ("true".equalsIgnoreCase(System.getenv("GOLDEN_UPDATE"))) {
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
        }
        String expected = Files.readString(golden, StandardCharsets.UTF_8);
        String[] expectedLines = expected.split("\\R", -1);
        String[] actualLines = actual.split("\\R", -1);
        int max = Math.max(expectedLines.length, actualLines.length);
        for (int index = 0; index < max; index++) {
            String left = index < expectedLines.length ? expectedLines[index] : "<缺失>";
            String right = index < actualLines.length ? actualLines[index] : "<缺失>";
            if (!left.equals(right)) {
                fail("parse-product-real 黄金快照第一处差异在第 " + (index + 1) + " 行\n期望: "
                        + left + "\n实际: " + right);
            }
        }
        assertEquals(expected, actual, "parse-product-real 黄金快照不一致");
    }
}
