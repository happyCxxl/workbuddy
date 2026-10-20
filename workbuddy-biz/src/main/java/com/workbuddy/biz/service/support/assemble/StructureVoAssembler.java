package com.workbuddy.biz.service.support.assemble;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.domain.structure.ConflictRecord;
import com.workbuddy.common.domain.structure.DocumentQuality;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.dto.response.structure.StructureCellVO;
import com.workbuddy.common.dto.response.structure.StructureConflictVO;
import com.workbuddy.common.dto.response.structure.StructureOutlineVO;
import com.workbuddy.common.dto.response.structure.StructureSummaryVO;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装详情 VO 组装器（纯映射，不查库）：UnifiedDocument 产物 → 统计/告警/冲突/大纲视图。
 * 产物由 Service 读好传入；任务/子步骤字段组装不归本类。
 */
@Component
public class StructureVoAssembler {

    /**
     * 组装统计：元素按类型计数 + 关系/冲突/告警数。
     *
     * @param document 统一文档产物
     * @return 结构统计视图
     */
    public StructureSummaryVO toSummary(UnifiedDocument document) {
        List<UnifiedElement> elements = ObjectUtil.defaultIfNull(document.getElements(), new ArrayList<>());
        DocumentQuality quality = ObjectUtil.defaultIfNull(document.getQuality(), new DocumentQuality());
        StructureSummaryVO summary = new StructureSummaryVO();
        summary.setElementCount(elements.size());
        summary.setTitleCount(countByType(elements, UnifiedElementType.TITLE));
        summary.setParagraphCount(countByType(elements, UnifiedElementType.PARAGRAPH));
        summary.setTableCount(countByType(elements, UnifiedElementType.TABLE));
        summary.setImageCount(countByType(elements, UnifiedElementType.IMAGE));
        summary.setRelationCount(ObjectUtil.defaultIfNull(document.getRelations(), new ArrayList<>()).size());
        summary.setConflictCount(ObjectUtil.defaultIfNull(quality.getConflicts(), new ArrayList<>()).size());
        summary.setWarningCount(ObjectUtil.defaultIfNull(quality.getWarnings(), new ArrayList<>()).size());
        return summary;
    }

    /**
     * 质量告警转展示文本（级别 + 码值 + 说明）。
     *
     * @param quality 文档质量
     * @return 告警文本列表；没有告警时为空列表
     */
    public List<String> toWarningTexts(DocumentQuality quality) {
        if (NullUtil.isNull(quality) || NullUtil.isNull(quality.getWarnings())) {
            return new ArrayList<>();
        }
        return quality.getWarnings().stream()
                .map(w -> w.getLevel() + " " + w.getCode() + ": " + w.getMessage())
                .toList();
    }

    /**
     * 冲突记录转冲突视图。
     *
     * @param quality 文档质量
     * @return 冲突视图列表；没有冲突时为空列表
     */
    public List<StructureConflictVO> toConflicts(DocumentQuality quality) {
        if (NullUtil.isNull(quality) || NullUtil.isNull(quality.getConflicts())) {
            return new ArrayList<>();
        }
        return quality.getConflicts().stream().map(this::toConflictVO).toList();
    }

    /**
     * 文档内容大纲：元素按阅读顺序全量返回（含标题/段落/表格/图片等），跳文档根与单元格。
     *
     * @param document 统一文档产物
     * @return 大纲视图列表
     */
    public List<StructureOutlineVO> toOutline(UnifiedDocument document) {
        List<UnifiedElement> elements = ObjectUtil.defaultIfNull(document.getElements(),
                new ArrayList<>());
        return elements.stream()
                .filter(e -> !UnifiedElementType.DOCUMENT.name().equals(e.getType())
                        && !UnifiedElementType.TABLE_CELL.name().equals(e.getType()))
                .map(this::toOutlineVO)
                .toList();
    }

    private int countByType(List<UnifiedElement> elements, UnifiedElementType type) {
        return (int) elements.stream().filter(e -> type.name().equals(e.getType())).count();
    }

    private StructureConflictVO toConflictVO(ConflictRecord record) {
        StructureConflictVO vo = new StructureConflictVO();
        vo.setPrimaryElementId(record.getPrimaryElementId());
        vo.setBackupElementId(record.getBackupElementId());
        vo.setMessage(record.getMessage());
        return vo;
    }

    private StructureOutlineVO toOutlineVO(UnifiedElement element) {
        StructureOutlineVO vo = new StructureOutlineVO();
        vo.setElementId(element.getId());
        vo.setType(element.getType());
        vo.setText(element.getText());
        vo.setLevel(element.getLevel());
        vo.setPage(element.getPage());
        vo.setPageRange(element.getPageRange());
        vo.setRows(element.getRows());
        vo.setCols(element.getCols());
        vo.setConflictStatus(element.getConflictStatus());
        vo.setCaption(element.getCaption());
        if (NullUtil.isNotNull(element.getCells())) {
            vo.setCells(element.getCells().stream().map(this::toCellVO).toList());
        }
        return vo;
    }

    private StructureCellVO toCellVO(UnifiedElement cell) {
        StructureCellVO vo = new StructureCellVO();
        vo.setRow(cell.getRow());
        vo.setCol(cell.getCol());
        vo.setText(cell.getText());
        vo.setIsHeader(cell.getIsHeader());
        return vo;
    }
}
