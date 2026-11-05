package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.common.enums.parse.ParseFactLevel;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.asset.PdfPageImageRenderer;
import com.workbuddy.worker.parser.pdf.detect.RuleSegments;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.Region;

import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;

/**
 * 页内图片元素与事实：面积占比达门槛的图片产 IMAGE 元素（仅引用 + needsOcr + bbox 定位），
 * 逐张产 OCR_IMAGE 事实（仅告警）；元素按页内自上而下追加在本页正文之后。
 */
public final class PdfImageEmitter {

    private PdfImageEmitter() {
    }

    /**
     * 产出本页图片元素与事实（面积占比低于门槛的图片不产元素）。
     *
     * @param page       单页提取内容
     * @param elements   产物元素（追加）
     * @param source     解析结果
     * @param fileId     文件 ID
     * @param properties 解析阈值配置
     * @param context    解析上下文（资产写入口）
     * @param renderer   页图渲染器（未启用资产时为 null）
     */
    public static void emit(PageContent page, List<ParseElement> elements, ParseSource source,
                     String fileId, ParseProperties properties, ParseContext context,
                     PdfPageImageRenderer renderer) {
        // OCR 候选页（扫描/乱码/CID 缺映射）先产整页图：无嵌入图片的乱码页也需要页图供 OCR 与预览
        BufferedImage pageImage = writePageImage(page, context, renderer, properties);
        if (page.imageBoxes().isEmpty()) {
            return;
        }
        double pageArea = page.pageWidth() * page.pageHeight();
        if (pageArea <= 0) {
            return;
        }
        List<Region> boxes = page.imageBoxes().stream()
                .filter(box -> RuleSegments.imageArea(box, page.pageWidth(), page.pageHeight())
                        >= pageArea * properties.getImageMinAreaRatio())
                .sorted(Comparator.comparingDouble(Region::top))
                .toList();
        int index = 0;
        double totalRatio = 0;
        for (Region box : boxes) {
            double ratio = RuleSegments.imageArea(box, page.pageWidth(), page.pageHeight()) / pageArea;
            ParseElement image = ParseElement.of(ElementType.IMAGE);
            image.setPage(page.pageNo());
            BBox bbox = page.geometry().toViewport(new BBox(box.left(), box.top(),
                    box.right() - box.left(), box.bottom() - box.top()));
            image.setAssetRef(writeCrop(page, pageImage, bbox, context, renderer));
            image.setNeedsOcr(true);
            image.setBbox(bbox);
            image.setProvenance(new Provenance(fileId,
                    "pdf#page(" + page.pageNo() + ")/image[" + index + "]"));
            elements.add(image);
            totalRatio += ratio;
            index++;
        }
        // 事实按页聚合：多图页只产一条（证据带张数与合计占比），图片元素仍逐张产出
        if (index > 0) {
            ParseFact fact = new ParseFact();
            fact.setType(SignalType.OCR_IMAGE);
            fact.setLevel(ParseFactLevel.WARN);
            fact.setRegion("page " + page.pageNo());
            fact.setEvidence(String.format("嵌入图片仅引用无文字（本页 %d 张，合计占页面积 %.2f）",
                    index, totalRatio));
            source.getFacts().add(fact);
        }
    }

    /** 页图发射条件：扫描页（无文本且图片覆盖达门槛）或乱码/CID 缺映射页（OCR 候选页，供 OCR 与预览使用）。 */
    private static boolean needsPageImage(PageMetric metric, ParseProperties properties) {
        if (metric.getCharCount() == 0) {
            return metric.getImageAreaRatio() >= properties.getScanPageImageRatio();
        }
        return metric.getGarbledRatio() > properties.getGarbledRateThreshold()
                || metric.getCidMappingRatio() >= properties.getCidMappingMinRatio();
    }

    /** 扫描页整页图：无文本且图片覆盖达扫描页门槛时写 PAGE_IMAGE，供 OCR 与预览使用。 */
    private static BufferedImage writePageImage(PageContent page, ParseContext context,
                                                PdfPageImageRenderer renderer, ParseProperties properties) {
        if (NullUtil.isNull(renderer) || !needsPageImage(page.metric(), properties)) {
            return null;
        }
        BufferedImage pageImage = renderer.pageImage(page.pageNo());
        BBox full = new BBox(0, 0, page.geometry().viewportWidth(), page.geometry().viewportHeight());
        context.getAssetSink().put(ParseAssetKind.PAGE_IMAGE, renderer.png(pageImage), "image/png",
                page.pageNo(), full, pageImage.getWidth(), pageImage.getHeight());
        return pageImage;
    }

    /** 图片区域裁剪资产：未启用资产写入或渲染器缺失时返回 null。 */
    private static String writeCrop(PageContent page, BufferedImage pageImage, BBox bbox,
                                    ParseContext context, PdfPageImageRenderer renderer) {
        if (NullUtil.isNull(renderer)) {
            return null;
        }
        BufferedImage source = NullUtil.isNotNull(pageImage) ? pageImage : renderer.pageImage(page.pageNo());
        BufferedImage crop = renderer.crop(source, bbox,
                page.geometry().viewportWidth(), page.geometry().viewportHeight());
        ParseAsset asset = context.getAssetSink().put(ParseAssetKind.IMAGE_CROP, renderer.png(crop), "image/png",
                page.pageNo(), bbox, crop.getWidth(), crop.getHeight());
        return NullUtil.isNull(asset) ? null : asset.getAssetId();
    }
}
