package com.workbuddy.biz.service.support.assemble;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.domain.structure.ConflictRecord;
import com.workbuddy.common.domain.structure.DocumentQuality;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.dto.response.structure.StructureCellVO;
import com.workbuddy.common.dto.response.structure.StructureConflictVO;
import com.workbuddy.common.dto.response.structure.StructureOutlineVO;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.utils.NullUtil;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装详情 VO 映射：统一文档 → 大纲/冲突/告警文本（编译期生成实现，运行期无反射）。
 *
 * <p>只做字段搬运；统计口径在统计支撑类。过滤（跳文档根与单元格）属展示口径，留在默认方法里。
 */
@Mapper(componentModel = "spring")
public interface StructureVoAssembler {

    /**
     * 单条冲突记录 → 冲突视图。
     *
     * @param record 冲突记录
     * @return 冲突视图
     */
    StructureConflictVO toConflictVO(ConflictRecord record);

    /**
     * 冲突记录列表 → 冲突视图列表。
     *
     * @param records 冲突记录列表
     * @return 冲突视图列表
     */
    List<StructureConflictVO> toConflictVOs(List<ConflictRecord> records);

    /**
     * 大纲元素 → 大纲视图（单元格明细随元素映射）。
     *
     * @param element 统一文档元素
     * @return 大纲视图
     */
    @Mapping(target = "elementId", source = "id")
    StructureOutlineVO toOutlineVO(UnifiedElement element);

    /**
     * 单元格元素 → 单元格视图。
     *
     * @param cell 单元格元素
     * @return 单元格视图
     */
    StructureCellVO toCellVO(UnifiedElement cell);

    /**
     * 质量告警转展示文本（级别 + 码值 + 说明）。
     *
     * @param quality 文档质量
     * @return 告警文本列表；没有告警时为空列表
     */
    default List<String> toWarningTexts(DocumentQuality quality) {
        if (NullUtil.isNull(quality) || NullUtil.isNull(quality.getWarnings())) {
            return new ArrayList<>();
        }
        return quality.getWarnings().stream()
                .map(w -> w.getLevel() + " " + w.getCode() + ": " + w.getMessage())
                .toList();
    }

    /**
     * 冲突记录转冲突视图；没有冲突时为空列表。
     *
     * @param quality 文档质量
     * @return 冲突视图列表
     */
    default List<StructureConflictVO> toConflicts(DocumentQuality quality) {
        if (NullUtil.isNull(quality) || NullUtil.isNull(quality.getConflicts())) {
            return new ArrayList<>();
        }
        return toConflictVOs(quality.getConflicts());
    }

    /**
     * 文档内容大纲：元素按阅读顺序全量返回，跳文档根与单元格。
     *
     * @param document 统一文档产物
     * @return 大纲视图列表
     */
    default List<StructureOutlineVO> toOutline(UnifiedDocument document) {
        List<UnifiedElement> elements = ObjectUtil.defaultIfNull(document.getElements(), new ArrayList<>());
        return elements.stream()
                .filter(e -> !UnifiedElementType.DOCUMENT.name().equals(e.getType())
                        && !UnifiedElementType.TABLE_CELL.name().equals(e.getType()))
                .map(this::toOutlineVO)
                .toList();
    }
}
