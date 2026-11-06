package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 解析成功占比门禁：按内容单元、空白页、扫描页与 OCR 恢复情况判定任务状态。
 * 会就地修正失败页清单与失败单元数，并把结论写入 {@link ParseOutcome}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParseQualityGate {

    private final ParseProperties properties;

    /**
     * 成功占比门槛评估：0 单元→PARSE_CORRUPTED；全空白页→PARSE_CORRUPTED；
     * 扫描页按 OCR 覆盖拆成恢复/未恢复：未恢复计失败，全恢复按 PARTIAL_SUCCESS 放行；
     * 短文档看坏页绝对数；占比不足→RATIO_BELOW_THRESHOLD。
     * 分母用内容单元（单元数 − 空白页数）：空白页无内容可解析，既不算成功也不算失败。
     *
     * @param outcome 解析产出（写入门禁结论与单元计数）
     * @param nativeSource 原生解析结果（取内容单元与页级指标）
     * @param ocrSource OCR 路结果（识别已恢复的扫描页）
     * @param failedUnits 信号降级阶段已计入的失败单元数
     * @param quality 质量信息（修正失败页清单，并读取空白页/扫描页）
     */
    public void evaluate(ParseOutcome outcome, ParseSource nativeSource, ParseSource ocrSource,
                         int failedUnits, QualityInfo quality) {
        int unitCount = NullUtil.isNull(nativeSource.getUnitCount()) ? 0 : nativeSource.getUnitCount();
        outcome.setUnitCount(unitCount);
        outcome.setFailedUnits(failedUnits);
        if (unitCount <= 0) {
            outcome.fail(PipelineTaskErrorCode.PARSE_CORRUPTED.name(), "无有效内容单元（0 页/0 sheet）");
            return;
        }
        Set<Integer> blankPages = quality.getBlankPages().stream().distinct().collect(Collectors.toSet());
        int contentUnits = unitCount - blankPages.size();
        if (contentUnits <= 0) {
            outcome.fail(PipelineTaskErrorCode.PARSE_CORRUPTED.name(),
                    "全为空白页（" + unitCount + " 个单元无文本也无图片）");
            return;
        }
        // 扫描页不再由 handler 直接计失败：OCR 覆盖的页算恢复，未覆盖的页由门禁计入失败
        Set<Integer> scannedPages = ParsePageSets.scannedPages(quality, blankPages);
        Set<Integer> ocrPages = ParsePageSets.ocrPages(ocrSource);
        Set<Integer> recoveredPages = scannedPages.stream()
                .filter(ocrPages::contains)
                .collect(Collectors.toSet());
        Set<Integer> unrecoveredPages = scannedPages.stream()
                .filter(page -> !ocrPages.contains(page))
                .collect(Collectors.toSet());
        int additionalFailedUnits = 0;
        for (Integer page : unrecoveredPages) {
            if (!quality.getFailedPages().contains(page)) {
                quality.getFailedPages().add(page);
                additionalFailedUnits++;
            }
        }
        // 乱码/CID 失败页被 OCR 覆盖即恢复：从失败计数扣除并移出失败页清单
        int recoveredFailedUnits = (int) quality.getFailedPages().stream()
                .filter(ocrPages::contains)
                .count();
        quality.getFailedPages().removeIf(ocrPages::contains);
        int effectiveFailedUnits = failedUnits + additionalFailedUnits - recoveredFailedUnits;
        outcome.setFailedUnits(effectiveFailedUnits);

        // 纯扫描件且 OCR 一页未恢复：能力未覆盖，整任务 FAILED；部分恢复时继续按占比判定
        if (!scannedPages.isEmpty() && scannedPages.size() >= contentUnits) {
            if (recoveredPages.isEmpty()) {
                outcome.fail(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(),
                        "扫描件暂不支持（OCR 未覆盖扫描页）");
                return;
            }
            if (recoveredPages.containsAll(scannedPages)) {
                outcome.setSuggestedStatus(PipelineTaskStatus.PARTIAL_SUCCESS);
                return;
            }
        }
        // 短文档：小分母上比例门槛等价零容错，改看坏页绝对数（坏页 ≤ 门槛且仍有可读单元即放行）
        if (contentUnits <= properties.getShortDocumentMaxUnits()
                && effectiveFailedUnits <= properties.getShortDocumentMaxFailedUnits()
                && contentUnits - effectiveFailedUnits >= 1) {
            outcome.setSuggestedStatus(effectiveFailedUnits == 0 && recoveredPages.isEmpty()
                    ? PipelineTaskStatus.SUCCESS
                    : PipelineTaskStatus.PARTIAL_SUCCESS);
            return;
        }
        double ratio = (double) (contentUnits - effectiveFailedUnits) / contentUnits;
        if (ratio >= properties.getSuccessUnitRatio()) {
            outcome.setSuggestedStatus(effectiveFailedUnits == 0 && recoveredPages.isEmpty()
                    ? PipelineTaskStatus.SUCCESS
                    : PipelineTaskStatus.PARTIAL_SUCCESS);
            return;
        }
        // 门槛不足：连每页原始指标一起打印（扫描件/乱码口径/文字占比口径）
        log.warn("===> ParseQualityGate 解析成功占比不足, unitCount={}, blankUnits={}, failedUnits={}, "
                        + "ratio={}, threshold={}, pageMetrics={}",
                unitCount, blankPages.size(), effectiveFailedUnits, String.format("%.2f", ratio),
                properties.getSuccessUnitRatio(),
                nativeSource.getPageMetrics().stream()
                        .map(m -> m.getPage() + ":chars=" + m.getCharCount()
                                + ",garbled=" + String.format("%.2f", m.getGarbledRatio())
                                + ",area=" + String.format("%.2f", m.getTextAreaRatio())
                                + ",image=" + String.format("%.2f", m.getImageAreaRatio()))
                        .toList());
        outcome.fail(PipelineTaskErrorCode.RATIO_BELOW_THRESHOLD.name(),
                String.format("成功单元占比 %.2f 低于门槛 %.2f（内容单元 %d，失败 %d）",
                        ratio, properties.getSuccessUnitRatio(), contentUnits, effectiveFailedUnits));
    }

}
