package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.pdf.asset.PdfPageImageRenderer;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.page.BlockClassifier;
import com.workbuddy.worker.parser.pdf.page.PageBlock;
import com.workbuddy.worker.parser.pdf.page.ParagraphAssembler;
import com.workbuddy.worker.parser.pdf.page.ParagraphBlock;
import com.workbuddy.worker.parser.pdf.page.RegionTableBlock;
import com.workbuddy.worker.parser.pdf.page.TableBlock;

import java.util.List;

/**
 * 页面块渲染：段落块产出段落，文本表格块走文本路径，线框区域块走线网格路径。
 */
public final class TextRunRenderer {

    private TextRunRenderer() {
    }

    /**
     * 渲染一组有序块。
     *
     * @param blocks 有序块
     * @param page 单页内容
     * @param elements 产物元素（追加）
     * @param source 页面局部解析结果
     * @param fileId 文件 ID
     * @param properties 解析配置
     * @param capabilityRegistry 能力注册表
     * @param context 解析上下文
     * @param renderer 页图渲染器
     */
    public static void render(List<PageBlock> blocks, PageContent page, List<ParseElement> elements,
                              ParseSource source, String fileId, ParseProperties properties,
                              CapabilityRegistry capabilityRegistry, ParseContext context,
                              PdfPageImageRenderer renderer) {
        for (PageBlock block : blocks) {
            switch (block) {
                case ParagraphBlock paragraph ->
                        ParagraphAssembler.addParagraph(paragraph.lines(), elements, fileId, page);
                case TableBlock table ->
                        TextTableBuilder.flush(table.lines(), page, elements, source, fileId, properties,
                                capabilityRegistry, context, renderer);
                case RegionTableBlock region ->
                        RuledTableBuilder.flush(region.lines(), region.region(), page, elements, source, fileId,
                                properties, capabilityRegistry, context, renderer);
            }
        }
    }

    /**
     * 旧文本路径入口：分类后立即渲染；供线网格失败时回退。
     *
     * @param run 连续行
     * @param page 单页内容
     * @param elements 产物元素（追加）
     * @param source 页面局部解析结果
     * @param fileId 文件 ID
     * @param properties 解析配置
     * @param capabilityRegistry 能力注册表
     * @param context 解析上下文
     * @param renderer 页图渲染器
     */
    public static void flush(List<PageLine> run, PageContent page, List<ParseElement> elements,
                             ParseSource source, String fileId, ParseProperties properties,
                             CapabilityRegistry capabilityRegistry, ParseContext context,
                             PdfPageImageRenderer renderer) {
        render(BlockClassifier.classify(run, properties), page, elements, source, fileId, properties,
                capabilityRegistry, context, renderer);
    }
}
