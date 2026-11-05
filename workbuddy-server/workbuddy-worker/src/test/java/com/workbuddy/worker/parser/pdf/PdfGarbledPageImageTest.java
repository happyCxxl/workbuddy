package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.assemble.PdfImageEmitter;
import com.workbuddy.worker.parser.pdf.asset.PdfPageImageRenderer;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import com.workbuddy.worker.parser.pdf.model.Region;
import com.workbuddy.worker.parser.pdf.model.Segment;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import com.workbuddy.worker.testkit.RecordingAssetSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * PDF 图片资产：乱码页（有文本但无嵌入图片）也产 PAGE_IMAGE，供 OCR 补文字（OCR 候选页口径）。
 */
@ExtendWith(MockitoExtension.class)
class PdfGarbledPageImageTest {

    @Mock
    private PdfPageImageRenderer renderer;

    /** 校验点：乱码页无嵌入图片，仍产 {@code ParseAssetKind#PAGE_IMAGE}。 */
    @Test
    void garbledTextPageWithoutImagesStillEmitsPageImage() {
        PageMetric metric = new PageMetric();
        metric.setPage(1);
        metric.setCharCount(200);
        metric.setGarbledRatio(0.5);
        metric.setImageAreaRatio(0.0);
        PageContent page = new PageContent(1, 612, 792, new PdfPageGeometry(612, 792, 0), metric,
                List.<PageLine>of(), List.<Segment>of(), List.<Region>of());
        ParseProperties properties = PropertiesFixtures.parse();
        RecordingAssetSink sink = new RecordingAssetSink();
        ParseContext context = new ParseContext();
        context.setAssetSink(sink);
        context.setProperties(properties);
        when(renderer.pageImage(1)).thenReturn(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB));
        when(renderer.png(org.mockito.ArgumentMatchers.any())).thenReturn(new byte[] {1});

        PdfImageEmitter.emit(page, new java.util.ArrayList<ParseElement>(), ParseSource.nativeSource("test"),
                "F-1", properties, context, renderer);

        assertTrue(sink.assets().stream().anyMatch(asset -> ParseAssetKind.PAGE_IMAGE == asset.getKind()),
                () -> "assets=" + sink.assets());
    }
}
