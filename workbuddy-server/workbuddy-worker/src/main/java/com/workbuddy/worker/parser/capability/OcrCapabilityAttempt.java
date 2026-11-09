package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.capability.OcrLine;
import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.utils.NullUtil;

import java.util.List;

/**
 * 扫描/乱码页的 OCR 能力尝试：按页面区域调一次结构化识别，成功给出「段落元素清单 + 能力补强事实」，
 * 未接入 / 区域为空 / 调用失败 / 结果不可用给出「空清单 + 能力降级事实」，由取用方决定该页是否保持原样。
 * 与版面、表格两个尝试门面同构：provider 异常按降级返回，不向上传播。
 */
public final class OcrCapabilityAttempt {

    private OcrCapabilityAttempt() {
    }

    /**
     * 尝试结果。
     *
     * @param paragraphs 识别行转出的段落元素（null 表示未产出）
     * @param fact       留痕事实（补强或降级）
     * @param usage      本次实际用到的能力引用（未产出时为 null）
     */
    public record Outcome(List<ParseElement> paragraphs, ParseFact fact, CapabilityUsage usage) {

        /** 未产出文字的结果（无能力引用） */
        public Outcome(List<ParseElement> paragraphs, ParseFact fact) {
            this(paragraphs, fact, null);
        }

        /**
         * 是否拿到可用文字。
         *
         * @return 有可用段落为 true
         */
        public boolean enhanced() {
            return NullUtil.isNotNull(paragraphs) && !paragraphs.isEmpty();
        }
    }

    /**
     * 对一页做一次 OCR 能力尝试。
     *
     * @param registry 能力注册表（null 表示未接入）
     * @param region   待识别区域（null 表示区域为空）
     * @param pageNo   页码
     * @param ctx      调用上下文（含任务 ID 与页图字节解析入口）
     * @return 尝试结果（永不为 null；provider 抛异常按降级返回，不向上传播）
     */
    public static Outcome attempt(CapabilityRegistry registry, OcrRegion region, int pageNo, ProviderContext ctx) {
        String traceRegion = "page " + pageNo;
        StructuredOcrProvider provider = NullUtil.isNull(registry) ? null : registry.ocr();
        if (NullUtil.isNull(provider)) {
            return new Outcome(null, CapabilityFacts.ocrDegraded(traceRegion, "未接入能力"));
        }
        if (NullUtil.isNull(region)) {
            return new Outcome(null, CapabilityFacts.ocrDegraded(traceRegion, "区域为空"));
        }
        StructuredOcrResult result;
        try {
            result = provider.recognize(region, ctx);
        } catch (RuntimeException exception) {
            return new Outcome(null,
                    CapabilityFacts.ocrDegraded(traceRegion, CapabilityFacts.degradedReason(exception)));
        }
        if (NullUtil.isNull(result) || NullUtil.isNull(result.getLines()) || result.getLines().isEmpty()) {
            return new Outcome(null, CapabilityFacts.ocrDegraded(traceRegion, "调用失败或结果不完整"));
        }
        List<ParseElement> paragraphs = result.getLines().stream()
                .map(line -> paragraph(line, pageNo))
                .toList();
        return new Outcome(paragraphs, CapabilityFacts.ocrEnhanced(traceRegion, result),
                new CapabilityUsage(CapabilityUsage.KIND_OCR, result.getModel(), result.getVersion()));
    }

    /** 识别行转段落元素：文本、页内包围盒与置信度，ID 与溯源归组装环节 */
    private static ParseElement paragraph(OcrLine line, int pageNo) {
        ParseElement element = ParseElement.of(ElementType.PARAGRAPH);
        element.setText(line.getText());
        element.setPage(pageNo);
        element.setBbox(line.getBbox());
        element.setConfidence(line.getConfidence());
        element.setNeedsOcr(false);
        return element;
    }
}
