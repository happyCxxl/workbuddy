package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.ParseSummary;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.parse.QualityWarning;
import com.workbuddy.common.enums.parse.QualityWarningCode;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 解析产物摘要工厂：生成质量维度汇总、计数摘要、阈值快照与配置哈希。
 * 只读解析结果与质量信息，不改变元素、页级指标或门禁结论。
 */
@Component
@RequiredArgsConstructor
public class ParseSummaryFactory {

    private final ParseProperties properties;

    /**
     * 质量维度汇总：坏页比例达到阈值时追加一条 {@code QUALITY_BAD_PAGE_RATIO} 告警。
     *
     * @param nativeSource 原生解析结果（取内容单元数）
     * @param quality 质量信息（追加汇总告警）
     */
    public void summarizeQuality(ParseSource nativeSource, QualityInfo quality) {
        int unitCount = NullUtil.isNull(nativeSource.getUnitCount()) ? 0 : nativeSource.getUnitCount();
        int contentUnits = unitCount - (int) quality.getBlankPages().stream().distinct().count();
        if (contentUnits <= 0) {
            return;
        }
        double badRatio = (double) quality.getFailedPages().size() / contentUnits;
        if (badRatio < properties.getQualityBadPageRatioThreshold()) {
            return;
        }
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.QUALITY_BAD_PAGE_RATIO, null, "WARN",
                String.format("坏页 %d/%d 个内容单元（%.2f ≥ %.2f）：%s", quality.getFailedPages().size(),
                        contentUnits, badRatio, properties.getQualityBadPageRatioThreshold(),
                        dimensionCounts(quality))));
    }

    /**
     * 生成产物摘要。
     *
     * @param outcome 解析结果（计数与失败单元）
     * @param nativeSource 原生解析结果
     * @param ocrSource ocr 路
     * @param quality 质量信息
     * @return 产物摘要
     */
    public ParseSummary build(ParseOutcome outcome, ParseSource nativeSource,
                              ParseSource ocrSource, QualityInfo quality) {
        ParseSummary summary = new ParseSummary();
        Set<Integer> blankPages = new HashSet<>(quality.getBlankPages());
        Set<Integer> scannedPages = ParsePageSets.scannedPages(quality, blankPages);
        Set<Integer> ocrPages = ParsePageSets.ocrPages(ocrSource);
        int unitCount = outcome.getUnitCount();
        summary.setUnitCount(unitCount);
        summary.setUnitType(nativeSource.getUnitType());
        summary.setFailedUnits(outcome.getFailedUnits());
        summary.setBlankUnits(blankPages.size());
        summary.setScannedUnits(scannedPages.size());
        summary.setOcrPages(ocrPages.size());
        summary.setOcrRecoveredUnits((int) scannedPages.stream().filter(ocrPages::contains).count());
        summary.setReadableUnits(Math.max(0, unitCount - blankPages.size() - outcome.getFailedUnits()));
        summary.setDegradedElements(degradedElements(nativeSource, ocrSource));
        summary.setFailedPages(quality.getFailedPages().stream().distinct().sorted().toList());
        summary.setThresholds(thresholds());
        summary.setConfigHash(configHash());
        return summary;
    }

    /** 汇总清单文案：各告警码条数（质量维度计数），全零时给「无异常维度」 */
    private String dimensionCounts(QualityInfo quality) {
        Map<String, Long> counts = quality.getWarnings().stream()
                .collect(Collectors.groupingBy(QualityWarning::getCode, LinkedHashMap::new,
                        Collectors.counting()));
        if (counts.isEmpty()) {
            return "无异常维度";
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining(" / "));
    }

    /** 元素降级数：native 路与 ocr 路合计。 */
    private int degradedElements(ParseSource nativeSource, ParseSource ocrSource) {
        int count = 0;
        for (ParseSource source : List.of(nativeSource, ocrSource)) {
            if (NullUtil.isNull(source) || NullUtil.isNull(source.getElements())) {
                continue;
            }
            count += (int) source.getElements().stream()
                    .filter(element -> Boolean.TRUE.equals(element.getDegraded()))
                    .count();
        }
        return count;
    }

    /** 判定关键阈值快照（kebab-case 键，便于与配置面直接对照）。 */
    private Map<String, Object> thresholds() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("page-window-size", properties.getPageWindowSize());
        thresholds.put("large-file-min-bytes", properties.getLargeFileMinBytes());
        thresholds.put("office-page-chars", properties.getOfficePageChars());
        thresholds.put("page-image-dpi", properties.getPageImageDpi());
        thresholds.put("scan-page-min-chars", properties.getScanPageMinChars());
        thresholds.put("scan-page-image-ratio", properties.getScanPageImageRatio());
        thresholds.put("garbled-rate-threshold", properties.getGarbledRateThreshold());
        thresholds.put("text-area-ratio-threshold", properties.getTextAreaRatioThreshold());
        thresholds.put("success-unit-ratio", properties.getSuccessUnitRatio());
        thresholds.put("short-document-max-units", properties.getShortDocumentMaxUnits());
        thresholds.put("short-document-max-failed-units", properties.getShortDocumentMaxFailedUnits());
        thresholds.put("quality-bad-page-ratio-threshold", properties.getQualityBadPageRatioThreshold());
        return thresholds;
    }

    /** 全量解析配置序列化后的 SHA-256。 */
    private String configHash() {
        try {
            byte[] bytes = Objects.requireNonNull(JsonUtil.toJsonStr(properties)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

}
