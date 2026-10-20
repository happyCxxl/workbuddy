package com.workbuddy.biz.service.support.assemble;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.rules.PreprocessViewRules;
import com.workbuddy.common.dto.response.preprocess.PreprocessCellVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessElementVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessFieldVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessSummaryVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessTraceVO;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.biz.service.support.stats.PreprocessStatsSupport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 预处理详情 VO 组装器（纯映射，不查库）：PreprocessView 产物 → 统计/视图元素/轨迹/字段/单元格视图。
 * 产物由 Service 读好传入；任务/子步骤字段组装不归本类。
 */
@Component
public class PreprocessVoAssembler {

    /**
     * 预处理统计（展示口径：检索文本与展示文本不同的元素数等；与步骤日志的规则改写计数口径不同）。
     *
     * @param view 预处理视图
     * @return 统计视图
     */
    public PreprocessSummaryVO toSummary(PreprocessView view) {
        List<ViewElement> elements = ObjectUtil.defaultIfNull(view.getElements(), new ArrayList<>());
        int excludedCount = PreprocessStatsSupport.countStatuses(elements, PreprocessViewRules.EXCLUDED_STATUSES);
        int repeatedCount =
                PreprocessStatsSupport.countStatuses(elements, List.of(ViewElementStatus.REPEATED.name()));
        PreprocessSummaryVO summary = new PreprocessSummaryVO();
        summary.setElementCount(elements.size());
        summary.setChangedCount(PreprocessStatsSupport.countChanged(elements));
        summary.setExcludedCount(excludedCount);
        summary.setRepeatedCount(repeatedCount);
        summary.setMarkedCount(PreprocessStatsSupport.countStatuses(elements, PreprocessStatsSupport.MARKED_STATUSES));
        summary.setChunkSkippedCount(excludedCount + repeatedCount);
        summary.setFieldCount(PreprocessStatsSupport.countFields(elements));
        summary.setStatusCounts(PreprocessStatsSupport.statusCounts(elements));
        return summary;
    }

    /**
     * 视图元素转元素 VO 列表（处理轨迹只透传非 KEEP 条目）。
     *
     * @param view 预处理视图
     * @return 元素 VO 列表
     */
    public List<PreprocessElementVO> toElementVOs(PreprocessView view) {
        return ObjectUtil.defaultIfNull(view.getElements(), new ArrayList<ViewElement>()).stream()
                .map(this::toElementVO)
                .toList();
    }

    private PreprocessElementVO toElementVO(ViewElement element) {
        PreprocessElementVO vo = new PreprocessElementVO();
        vo.setElementId(element.getElementId());
        vo.setType(element.getType());
        vo.setStatus(element.getStatus());
        vo.setPage(element.getPage());
        vo.setRawText(element.getRawText());
        vo.setDisplayText(element.getDisplayText());
        vo.setNormalizedText(element.getNormalizedText());
        if (NullUtil.isNotNull(element.getNormalizedFields())) {
            vo.setFields(element.getNormalizedFields().stream().map(this::toFieldVO).toList());
        }
        // 处理轨迹只透传非 KEEP 条目（KEEP=未命中改写，前端差异化展示用不到）
        if (NullUtil.isNotNull(element.getPreprocessTrace())) {
            vo.setTrace(element.getPreprocessTrace().stream()
                    .filter(entry -> !TraceEntry.ACTION_KEEP.equals(entry.getAction()))
                    .map(this::toTraceVO)
                    .toList());
        }
        if (NullUtil.isNotNull(element.getCells())) {
            vo.setCells(element.getCells().stream().map(this::toCellVO).toList());
        }
        return vo;
    }

    private PreprocessTraceVO toTraceVO(TraceEntry entry) {
        PreprocessTraceVO vo = new PreprocessTraceVO();
        vo.setRule(entry.getRule());
        vo.setField(entry.getField());
        vo.setAction(entry.getAction());
        vo.setBefore(entry.getBefore());
        vo.setAfter(entry.getAfter());
        vo.setEvidence(entry.getEvidence());
        return vo;
    }

    private PreprocessFieldVO toFieldVO(NormalizedField field) {
        PreprocessFieldVO vo = new PreprocessFieldVO();
        vo.setField(field.getField());
        vo.setValue(field.getValue());
        vo.setUnit(field.getUnit());
        vo.setRule(field.getRule());
        return vo;
    }

    private PreprocessCellVO toCellVO(ViewCell cell) {
        PreprocessCellVO vo = new PreprocessCellVO();
        vo.setCellId(cell.getCellId());
        vo.setText(cell.getText());
        vo.setRow(cell.getRow());
        vo.setCol(cell.getCol());
        vo.setIsHeader(cell.getIsHeader());
        vo.setNormalizedText(cell.getNormalizedText());
        return vo;
    }
}
