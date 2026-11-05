package com.workbuddy.worker.structure.impl;

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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 结构环节真实产物回归：混合 PDF 的解析产物（native 数字页 + ocr 扫描页，含无文本的 IMAGE 元素）
 * 经 hutool 反序列化后组装不抛异常——守护去重合并对 null 文本的容错（IMAGE 元素无 text）。
 */
class StructureRealProductAssemblyTest {

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
    }
}
