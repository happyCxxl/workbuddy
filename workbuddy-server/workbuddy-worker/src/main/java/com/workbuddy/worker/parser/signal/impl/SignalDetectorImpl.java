package com.workbuddy.worker.parser.signal.impl;

import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.domain.parse.signal.Signal;
import com.workbuddy.common.enums.parse.SignalSubtype;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.signal.SignalDetector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 信号判定器实现：页级指标按阈值判定（先便宜后贵）+ 解析事实直接转信号。
 * 全链执行、聚合信号（命中不中断，一个文件可同时命中多类信号）。
 * 每个信号显式携带子类型（SignalSubtype），管线分支不依赖 evidence 文案。
 */
@Component
public class SignalDetectorImpl implements SignalDetector {

    /**
     * 检测解析结果里的质量信号（扫描件、乱码、表格与版面线索等）。
     *
     * @param nativeSource 原生解析结果
     * @param context      解析上下文
     * @return 信号列表
     */
    @Override
    public List<Signal> detect(ParseSource nativeSource, ParseContext context) {
        List<Signal> signals = new ArrayList<>();
        if (NullUtil.isNull(nativeSource) || NullUtil.isNull(context.getProperties())) {
            return signals;
        }
        ParseProperties p = context.getProperties();

        // ① 单元级指标 → 阈值信号（先便宜后贵：整单元无文本 → 文字极少/文字占比低 → 乱码率）
        if (NullUtil.isNotNull(nativeSource.getPageMetrics())) {
            for (var metric : nativeSource.getPageMetrics()) {
                String page = "page " + metric.getPage();
                String imageEvidence = imageEvidence(metric, p);
                if (metric.getCharCount() <= 0) {
                    // 整单元无文本：按图片口径分流扫描单元，再按矢量覆盖分流纯矢量页与空白单元
                    if (hasImage(metric, p)) {
                        signals.add(Signal.of(SignalType.OCR_TEXT, page,
                                "整单元无文本且含图片（扫描单元）", imageEvidence, SignalSubtype.SCANNED));
                    } else if (metric.getVectorAreaRatio() >= p.getVectorPageMinRatio()) {
                        signals.add(Signal.of(SignalType.OCR_TEXT, page,
                                "整单元无文本无图片但有矢量图形覆盖（纯矢量页）",
                                String.format("vectorArea=%.2f(≥%.2f)", metric.getVectorAreaRatio(),
                                        p.getVectorPageMinRatio()),
                                SignalSubtype.VECTOR_GRAPHIC));
                    } else {
                        signals.add(Signal.of(SignalType.OCR_TEXT, page,
                                "整单元无文本且无图片（空白单元）", imageEvidence, SignalSubtype.BLANK));
                    }
                } else if (isSuspectImageUnit(metric, p)) {
                    signals.add(Signal.of(SignalType.OCR_IMAGE, page,
                            "文字极少或文字占比低（疑似图片单元）",
                            suspectImageEvidence(metric, p),
                            SignalSubtype.IMAGE_LOW_RATIO));
                } else if (metric.getGarbledRatio() > p.getGarbledRateThreshold()) {
                    // 乱码页：字符主要来自缺 Unicode 映射的 CID 字体时单列子类型，降级分支可区分根因
                    boolean cidMappingFailed = metric.getCidMappingRatio() >= p.getCidMappingMinRatio();
                    signals.add(Signal.of(SignalType.OCR_TEXT, page,
                            cidMappingFailed ? "CID 字体缺 Unicode 映射" : "乱码率超阈值",
                            cidMappingFailed
                                    ? String.format("cidMapping=%.2f(≥%.2f),garbled=%.2f>%.2f",
                                            metric.getCidMappingRatio(), p.getCidMappingMinRatio(),
                                            metric.getGarbledRatio(), p.getGarbledRateThreshold())
                                    : String.format("%.2f>%.2f", metric.getGarbledRatio(),
                                            p.getGarbledRateThreshold()),
                            cidMappingFailed ? SignalSubtype.CID_MAPPING_FAILED : SignalSubtype.GARBLED));
                }
            }
        }

        // ② 解析事实 → 信号（表格规则失败 / 版面异常 / 结构缺口；子类型按事实类型推导）
    if (NullUtil.isNotNull(nativeSource.getFacts())) {
            for (ParseFact fact : nativeSource.getFacts()) {
                if (NullUtil.isNull(fact.getType())) {
                    continue;
                }
                signals.add(Signal.of(fact.getType(), fact.getRegion(), fact.getEvidence(), "-",
                        subtypeOfFact(fact.getType())));
            }
        }
        return signals;
    }

    /**
     * 单元是否按"图片单元"处理：PDF 看图片覆盖面积，Office 看内嵌图片数
     * （两条口径由解析器互斥回填：PDF 只填面积、Office 只填图片数）。
     */
    private boolean hasImage(PageMetric metric, ParseProperties properties) {
        return metric.getImageAreaRatio() >= properties.getScanPageImageRatio() || metric.getImageCount() > 0;
    }

    /** 图片口径证据文案（面积门槛与内嵌图片数） */
    private String imageEvidence(PageMetric metric, ParseProperties properties) {
        return String.format("imageArea=%.2f(≥%.2f),imageCount=%d",
                metric.getImageAreaRatio(), properties.getScanPageImageRatio(), metric.getImageCount());
    }

    /**
     * 疑似图片单元（两条口径任一成立）：
     * ① 文字占比低且有图片覆盖 —— PDF 侧必须同时有图片证据，不能把稀疏文字页/表格页误判为图片页；
     * ② 有内嵌图片且字符极少 —— Office 侧口径（DOC/DOCX/XLS/XLSX/PPT/PPTX 不回填面积类指标，
     *    判定只看「内嵌图片数 + 字符数」组合）。
     */
    private boolean isSuspectImageUnit(PageMetric metric, ParseProperties p) {
        boolean lowTextRatio = metric.getTextAreaRatio() > 0
                && metric.getTextAreaRatio() < p.getTextAreaRatioThreshold()
                && hasImage(metric, p);
        boolean imageWithFewChars = metric.getImageCount() > 0
                && metric.getCharCount() < p.getScanPageMinChars();
        return lowTextRatio || imageWithFewChars;
    }

    /** 疑似图片单元的证据：点名命中的是哪条口径（文字占比低 / 图片多字符少） */
    private String suspectImageEvidence(PageMetric metric, ParseProperties p) {
        if (metric.getTextAreaRatio() > 0 && metric.getTextAreaRatio() < p.getTextAreaRatioThreshold()) {
            return String.format("textArea=%.3f(<%.3f),chars=%d,imageCount=%d", metric.getTextAreaRatio(),
                    p.getTextAreaRatioThreshold(), metric.getCharCount(), metric.getImageCount());
        }
        return String.format("chars=%d(<%d),imageCount=%d（无面积口径，按图片数与字符数判定）",
                metric.getCharCount(), p.getScanPageMinChars(), metric.getImageCount());
    }

    /** 解析事实类型 → 信号子类型（OCR_IMAGE 事实均为嵌入图片；无对应子类型返回 null）。 */
    private SignalSubtype subtypeOfFact(SignalType factType) {
        return switch (factType) {
            case OCR_IMAGE -> SignalSubtype.IMAGE_EMBEDDED;
            case TABLE -> SignalSubtype.TABLE_RULE_FAILED;
            case LAYOUT -> SignalSubtype.LAYOUT_RULE_FAILED;
            case OCR_TEXT -> null;
        };
    }
}
