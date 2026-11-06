package com.workbuddy.worker.parser;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.capability.OcrLine;
import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.capability.CapabilityFacts;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * OCR 回退协作器：根据质量信号确定待补页，调用结构化 OCR 能力并把识别结果转为 ocr 路。
 * 只产出 ocr 路，不合并 native 路；未接入能力或单页失败按页记降级事实。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrFallbackCoordinator {

    private final CapabilityRegistry capabilityRegistry;

    /**
     * 构造 ocr 路。
     *
     * @param nativeSource 原生解析结果（取页尺寸）
     * @param quality 质量信息（扫描页与失败页）
     * @param context 解析上下文（取消、资产读取）
     * @return ocr 路解析结果
     */
    public ParseSource build(ParseSource nativeSource, QualityInfo quality, ParseContext context) {
        ParseSource ocr = new ParseSource();
        ocr.setSource("ocr");
        ocr.setCandidateOrder(false);
        ocr.setNote("OCR 能力未接入：接入后扫描/乱码页文字走本路，与 native 路重叠区域由组装环节裁决");
        StructuredOcrProvider provider = NullUtil.isNull(capabilityRegistry) ? null : capabilityRegistry.ocr();
        if (NullUtil.isNull(provider)) {
            return ocr;
        }
        Set<Integer> candidates = new TreeSet<>();
        candidates.addAll(quality.getScannedPages());
        candidates.addAll(quality.getFailedPages());
        int filledPages = 0;
        for (Integer pageNo : candidates) {
            context.getCancellationToken().throwIfCancelled();
            String region = "page " + pageNo;
            OcrRegion ocrRegion = new OcrRegion();
            ocrRegion.setPage(pageNo);
            ocrRegion.setImageRef(pageAssetId(context, pageNo));
            ocrRegion.setBbox(pageBox(nativeSource, pageNo));
            ProviderContext providerContext = new ProviderContext();
            providerContext.setTaskId(context.getFileResultId());
            providerContext.setAssetBytes(imageRef -> context.getAssetSink().read(imageRef));
            StructuredOcrResult recognized;
            try {
                recognized = provider.recognize(ocrRegion, providerContext);
            } catch (RuntimeException e) {
                log.warn("===> OcrFallbackCoordinator OCR 识别失败, fileResultId={}, region={}, error={}",
                        context.getFileResultId(), region,
                        StrUtil.maxLength(String.valueOf(e.getMessage()), 200));
                recognized = null;
            }
            if (NullUtil.isNull(recognized) || NullUtil.isNull(recognized.getLines())
                    || recognized.getLines().isEmpty()) {
                ocr.getFacts().add(CapabilityFacts.ocrDegraded(region, "调用失败或结果不完整"));
                continue;
            }
            filledPages++;
            for (OcrLine line : recognized.getLines()) {
                ocr.getElements().add(ocrParagraph(line, pageNo));
            }
            ocr.getFacts().add(CapabilityFacts.ocrEnhanced(region, recognized));
            if (ocr.getCapabilities().isEmpty()) {
                ocr.getCapabilities().add(
                        new CapabilityUsage(CapabilityUsage.KIND_OCR, recognized.getModel(), recognized.getVersion()));
            }
        }
        ocr.setNote(filledPages == 0
                ? "OCR 能力已接入但本档无可补文字的页（扫描页与乱码/CID 失败页）"
                : "OCR 能力已接入：" + filledPages + " 页文字由能力补出，与 native 路重叠区域由组装环节裁决");
        return ocr;
    }

    /** 识别行转段落元素：文本、页内包围盒与置信度，ID 与溯源归组装环节 */
    private ParseElement ocrParagraph(OcrLine line, Integer pageNo) {
        ParseElement element = ParseElement.of(ElementType.PARAGRAPH);
        element.setText(line.getText());
        element.setPage(pageNo);
        element.setBbox(line.getBbox());
        element.setConfidence(line.getConfidence());
        element.setNeedsOcr(false);
        return element;
    }

    /** 页图资产 ID：同一页有多张时取第一张；没有页图时返回 null（OCR 仍可按页发起）。 */
    private String pageAssetId(ParseContext context, Integer pageNo) {
        if (NullUtil.isNull(pageNo)) {
            return null;
        }
        return context.getAssetSink().assets().stream()
                .filter(asset -> ParseAssetKind.PAGE_IMAGE == asset.getKind())
                .filter(asset -> pageNo.equals(asset.getPage()))
                .map(ParseAsset::getAssetId)
                .findFirst()
                .orElse(null);
    }

    /** 页包围盒：按页尺寸取整页（页尺寸缺失时给 null，区域仍可发起调用） */
    private BBox pageBox(ParseSource nativeSource, Integer pageNo) {
        for (PageDimension dimension : ObjectUtil.defaultIfNull(nativeSource.getPageDimensions(),
                List.<PageDimension>of())) {
            if (NullUtil.isNotNull(pageNo) && pageNo.equals(dimension.getPage())) {
                return new BBox(0, 0, dimension.getWidth(), dimension.getHeight());
            }
        }
        return null;
    }
}
