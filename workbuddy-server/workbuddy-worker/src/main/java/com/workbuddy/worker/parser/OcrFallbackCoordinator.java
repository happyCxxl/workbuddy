package com.workbuddy.worker.parser;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.OcrCapabilityAttempt;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * OCR 回退协作器：根据质量信号确定待补页，逐页发起结构化 OCR 能力尝试并把识别结果转为 ocr 路。
 * 只产出 ocr 路，不合并 native 路；单页失败由 {@link OcrCapabilityAttempt} 按页记降级事实。
 */
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
            ProviderContext providerContext = new ProviderContext();
            providerContext.setTaskId(context.getKnowledgeFileId());
            providerContext.setAssetBytes(imageRef -> context.getAssetSink().read(imageRef));
            OcrCapabilityAttempt.Outcome outcome = OcrCapabilityAttempt.attempt(
                    capabilityRegistry, region(nativeSource, context, pageNo), pageNo, providerContext);
            ocr.getFacts().add(outcome.fact());
            if (outcome.enhanced()) {
                filledPages++;
                ocr.getElements().addAll(outcome.paragraphs());
                if (ocr.getCapabilities().isEmpty()) {
                    ocr.getCapabilities().add(outcome.usage());
                }
            }
        }
        ocr.setNote(filledPages == 0
                ? "OCR 能力已接入但本档无可补文字的页（扫描页与乱码/CID 失败页）"
                : "OCR 能力已接入：" + filledPages + " 页文字由能力补出，与 native 路重叠区域由组装环节裁决");
        return ocr;
    }

    /** 候选页的请求区域：页码 + 页图资产引用 + 整页包围盒；页图缺失时引用为 null（仍可按页发起） */
    private OcrRegion region(ParseSource nativeSource, ParseContext context, Integer pageNo) {
        OcrRegion ocrRegion = new OcrRegion();
        ocrRegion.setPage(pageNo);
        ocrRegion.setImageRef(pageAssetId(context, pageNo));
        ocrRegion.setBbox(pageBox(nativeSource, pageNo));
        return ocrRegion;
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
