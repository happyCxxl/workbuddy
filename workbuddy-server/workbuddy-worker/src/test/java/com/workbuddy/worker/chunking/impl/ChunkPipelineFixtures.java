package com.workbuddy.worker.chunking.impl;

import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.slice.SliceStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.chunking.impl.body.FixedWindowSliceStrategy;
import com.workbuddy.worker.chunking.impl.body.ParagraphSliceStrategy;
import com.workbuddy.worker.chunking.impl.body.SentenceAggregateSliceStrategy;
import com.workbuddy.worker.chunking.impl.body.StructureHybridSliceStrategy;
import com.workbuddy.worker.chunking.impl.body.TitleBoundarySliceStrategy;
import com.workbuddy.worker.chunking.impl.fallback.FixedWindowFallback;
import com.workbuddy.worker.chunking.impl.fallback.NoneFallback;
import com.workbuddy.worker.chunking.impl.fallback.RecursiveLengthFallback;
import com.workbuddy.worker.chunking.impl.image.ImageCaptionSliceStrategy;
import com.workbuddy.worker.chunking.impl.post.MinMergeProcessor;
import com.workbuddy.worker.chunking.impl.post.StructureOverlapProcessor;
import com.workbuddy.worker.chunking.impl.post.TitleInContentProcessor;
import com.workbuddy.worker.chunking.impl.table.TableContextMergedStrategy;
import com.workbuddy.worker.chunking.impl.table.TableRowGroupStrategy;
import com.workbuddy.worker.chunking.impl.table.TableRowSliceStrategy;
import com.workbuddy.worker.chunking.impl.table.TableWholeStrategy;
import com.workbuddy.worker.testkit.StrategyFixtures;

import java.util.ArrayList;
import java.util.List;
import com.workbuddy.worker.testkit.PropertiesFixtures;

/** 切片管线测试夹具：注册全部切片算法、构造视图/结构文档与策略上下文。 */
final class ChunkPipelineFixtures {

    private ChunkPipelineFixtures() {
    }

    static ChunkPipeline pipeline() {
        ChunkProperties props = PropertiesFixtures.chunk();
        return new ChunkPipeline(
                new SliceStrategyRegistry(List.of(
                        new ParagraphSliceStrategy(), new StructureHybridSliceStrategy(),
                        new TitleBoundarySliceStrategy(), new SentenceAggregateSliceStrategy(),
                        new FixedWindowSliceStrategy(),
                        new TableRowSliceStrategy(), new TableRowGroupStrategy(),
                        new TableWholeStrategy(), new TableContextMergedStrategy(),
                        new ImageCaptionSliceStrategy())),
                new FallbackSlicerRegistry(List.of(new RecursiveLengthFallback(),
                        new FixedWindowFallback(), new NoneFallback())),
                props,
                List.of(new StructureOverlapProcessor(), new MinMergeProcessor(), new TitleInContentProcessor()));
    }

    static ChunkPipeline pipelineWith(SliceStrategy... strategies) {
        List<SliceStrategy> all = new ArrayList<>(List.of(strategies));
        all.add(new ParagraphSliceStrategy());
        return new ChunkPipeline(
                new SliceStrategyRegistry(all),
                new FallbackSlicerRegistry(List.of(new RecursiveLengthFallback())),
                PropertiesFixtures.chunk(),
                List.of(new StructureOverlapProcessor(), new MinMergeProcessor(), new TitleInContentProcessor()));
    }

    static ViewElement viewElement(String id, String type, Integer page, String text, String status) {
        ViewElement element = new ViewElement();
        element.setElementId(id);
        element.setType(type);
        element.setPage(page);
        element.setRawText(text);
        element.setDisplayText(text);
        element.setNormalizedText(text);
        element.setStatus(status);
        return element;
    }

    static UnifiedElement unifiedElement(String id, String type, Integer level, String caption) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(type);
        element.setLevel(level);
        element.setCaption(caption);
        return element;
    }

    static ChunkContext context(PreprocessView view, UnifiedDocument document) {
        ChunkContext context = new ChunkContext();
        context.setView(view);
        context.setDocument(document);
        ChunkProperties props = PropertiesFixtures.chunk();
        context.setStrategy(StrategyFixtures.chunkDefaults(props));
        context.setFileResultId(10L);
        context.setUpstreamProductRef(50L);
        return context;
    }

    static ChunkContext context(PreprocessView view, UnifiedDocument document, String configJson) {
        ChunkContext context = context(view, document);
        context.setStrategy(new ChunkStrategyParser(PropertiesFixtures.chunk()).parse(configJson));
        return context;
    }

    static PreprocessView view(List<ViewElement> elements) {
        PreprocessView view = new PreprocessView();
        view.setDocumentId("doc-10");
        view.setElements(elements);
        return view;
    }

    static UnifiedDocument document(List<UnifiedElement> elements) {
        UnifiedDocument document = new UnifiedDocument();
        DocumentInfo info = new DocumentInfo();
        info.setDocumentId("doc-10");
        document.setDocumentInfo(info);
        document.setElements(elements);
        return document;
    }

    static ViewElement tableElement() {
        ViewElement table = viewElement("t-1", UnifiedElementType.TABLE.name(), 2, null,
                ViewElementStatus.NORMAL.name());
        ViewCell h1 = new ViewCell();
        h1.setCellId("tc-h1");
        h1.setText("评分项");
        h1.setNormalizedText("评分项");
        ViewCell h2 = new ViewCell();
        h2.setCellId("tc-h2");
        h2.setText("分值");
        h2.setNormalizedText("分值");
        ViewCell c1 = new ViewCell();
        c1.setCellId("tc-1");
        c1.setText("A1 报价");
        c1.setNormalizedText("A1 报价");
        ViewCell c2 = new ViewCell();
        c2.setCellId("tc-2");
        c2.setText("30");
        c2.setNormalizedText("30");
        table.setCells(List.of(h1, h2, c1, c2));
        return table;
    }

    static UnifiedElement tableStruct() {
        UnifiedElement tableStruct = unifiedElement("t-1", UnifiedElementType.TABLE.name(), null, null);
        tableStruct.setCells(List.of(
                cell("tc-h1", 0, 0, true, "评分项"),
                cell("tc-h2", 0, 1, true, "分值"),
                cell("tc-1", 1, 0, false, "A1 报价"),
                cell("tc-2", 1, 1, false, "30")));
        tableStruct.setPage(2);
        return tableStruct;
    }

    private static UnifiedElement cell(String id, int row, int col, boolean header, String text) {
        UnifiedElement cell = unifiedElement(id, UnifiedElementType.TABLE_CELL.name(), null, null);
        cell.setRow(row);
        cell.setCol(col);
        cell.setIsHeader(header);
        cell.setText(text);
        return cell;
    }
}
