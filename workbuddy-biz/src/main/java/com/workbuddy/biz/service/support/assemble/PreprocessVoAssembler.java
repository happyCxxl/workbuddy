package com.workbuddy.biz.service.support.assemble;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.biz.service.support.stats.PreprocessStatsSupport;
import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.dto.response.preprocess.PreprocessCellVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessElementVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessFieldVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessTraceVO;
import com.workbuddy.common.utils.NullUtil;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.ArrayList;
import java.util.List;

/**
 * 预处理详情 VO 映射：PreprocessView 产物 → 视图元素/轨迹/字段/单元格视图（编译期生成实现，运行期无反射）。
 *
 * <p>只做字段搬运；轨迹只透传非 KEEP 条目，过滤口径在统计支撑类。
 */
@Mapper(componentModel = "spring")
public interface PreprocessVoAssembler {

    /**
     * 归一化字段 → 字段视图。
     *
     * @param field 归一化字段
     * @return 字段视图
     */
    PreprocessFieldVO toFieldVO(NormalizedField field);

    /**
     * 处理轨迹条目 → 轨迹视图。
     *
     * @param entry 轨迹条目
     * @return 轨迹视图
     */
    PreprocessTraceVO toTraceVO(TraceEntry entry);

    /**
     * 表格单元格 → 单元格视图。
     *
     * @param cell 视图单元格
     * @return 单元格视图
     */
    PreprocessCellVO toCellVO(ViewCell cell);

    /**
     * 视图元素 → 元素视图（字段明细随元素映射；轨迹由默认方法补齐）。
     *
     * @param element 视图元素
     * @return 元素视图
     */
    @Mapping(target = "fields", source = "normalizedFields")
    @Mapping(target = "trace", ignore = true)
    PreprocessElementVO mapElement(ViewElement element);

    /**
     * 视图元素转元素 VO：补齐非 KEEP 的处理轨迹。
     *
     * @param element 视图元素
     * @return 元素 VO
     */
    default PreprocessElementVO toElementVO(ViewElement element) {
        PreprocessElementVO vo = mapElement(element);
        if (NullUtil.isNotNull(element.getPreprocessTrace())) {
            vo.setTrace(PreprocessStatsSupport.visibleTrace(element).stream()
                    .map(this::toTraceVO)
                    .toList());
        }
        return vo;
    }

    /**
     * 视图元素转元素 VO 列表。
     *
     * @param view 预处理视图
     * @return 元素 VO 列表
     */
    default List<PreprocessElementVO> toElementVOs(PreprocessView view) {
        return ObjectUtil.defaultIfNull(view.getElements(), new ArrayList<ViewElement>()).stream()
                .map(this::toElementVO)
                .toList();
    }
}
