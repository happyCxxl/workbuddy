package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.detect.RuleLines;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.Region;

import java.util.Comparator;
import java.util.List;

/**
 * 页内图片元素与事实：面积占比达门槛的图片产 IMAGE 元素（仅引用 + needsOcr + bbox 定位），
 * 逐张产 OCR_IMAGE 事实（仅告警）；元素按页内自上而下追加在本页正文之后。
 *
 * @author cxxl
 */
public final class PdfImageEmitter {

    private PdfImageEmitter() {
    }

    /** 产出本页图片元素与事实（面积占比低于门槛的图片不产元素） */
    public static void emit(PageContent page, List<ParseElement> elements, ParseSource source,
                     String fileId, ParseProperties properties) {
        if (page.imageBoxes().isEmpty()) {
            return;
        }
        double pageArea = page.pageWidth() * page.pageHeight();
        if (pageArea <= 0) {
            return;
        }
        List<Region> boxes = page.imageBoxes().stream()
                .filter(box -> RuleLines.imageArea(box, page.pageWidth(), page.pageHeight())
                        >= pageArea * properties.getImageMinAreaRatio())
                .sorted(Comparator.comparingDouble(Region::top))
                .toList();
        int index = 0;
        double totalRatio = 0;
        for (Region box : boxes) {
            double ratio = RuleLines.imageArea(box, page.pageWidth(), page.pageHeight()) / pageArea;
            ParseElement image = ParseElement.of(ElementType.IMAGE);
            image.setPage(page.pageNo());
            image.setAssetRef("page" + page.pageNo() + "-image" + index);
            image.setNeedsOcr(true);
            image.setBbox(page.geometry().toViewport(new BBox(box.left(), box.top(),
                    box.right() - box.left(), box.bottom() - box.top())));
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
            fact.setRegion("page " + page.pageNo());
            fact.setEvidence(String.format("嵌入图片仅引用无文字（本页 %d 张，合计占页面积 %.2f）",
                    index, totalRatio));
            source.getFacts().add(fact);
        }
    }
}
