package com.workbuddy.common.convert.knowledge;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.config.BaseInfoConvertConfig;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.structure.ConflictRecord;
import com.workbuddy.common.domain.structure.DocumentQuality;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.dto.response.stagecontent.BBoxVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
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
 * 组装转换：统一文档/冲突 → VO，以及环节条目骨架装配（itemOf/putPageGrid/putBbox）。只搬字段，不查库、不算统计。
 */
@Mapper(config = BaseInfoConvertConfig.class)
public interface StructureConverter {

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

    /**
     * 环节条目骨架：序号/类型/状态/展示文案/附加信息。
     *
     * @param seq     顺序号
     * @param type    类型
     * @param status  状态
     * @param display 展示文案
     * @param extra   附加信息
     * @return 条目 VO
     */
    default StageContentItemVO itemOf(int seq, String type, String status, String display,
                                      StageContentExtraVO extra) {
        StageContentItemVO item = new StageContentItemVO();
        item.setAlignKey(String.valueOf(seq));
        item.setSeq(seq);
        item.setType(type);
        item.setStatus(status);
        item.setDisplay(display);
        item.setExtra(extra);
        return item;
    }

    /**
     * 页码网格附加信息：字段非空才覆盖。
     *
     * @param extra 附加信息（就地修改）
     * @param page  页码（可空）
     * @param rows  行数（可空）
     * @param cols  列数（可空）
     */
    default void putPageGrid(StageContentExtraVO extra, Integer page, Integer rows, Integer cols) {
        if (NullUtil.isNotNull(page)) {
            extra.setPage(page);
        }
        if (NullUtil.isNotNull(rows)) {
            extra.setRows(rows);
        }
        if (NullUtil.isNotNull(cols)) {
            extra.setCols(cols);
        }
    }

    /**
     * 包围盒附加信息：空包围盒忽略。
     *
     * @param extra 附加信息（就地修改）
     * @param bbox  包围盒（可空）
     */
    default void putBbox(StageContentExtraVO extra, BBox bbox) {
        if (NullUtil.isNull(bbox)) {
            return;
        }
        BBoxVO box = new BBoxVO();
        box.setX(bbox.getX());
        box.setY(bbox.getY());
        box.setWidth(bbox.getWidth());
        box.setHeight(bbox.getHeight());
        extra.setBbox(box);
    }
}
