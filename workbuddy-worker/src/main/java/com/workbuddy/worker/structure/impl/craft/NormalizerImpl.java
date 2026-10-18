package com.workbuddy.worker.structure.impl.craft;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.parse.ParseSourceType;
import com.workbuddy.common.enums.structure.ElementExtensionKey;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.craft.ElementNormalizer;
import com.workbuddy.worker.structure.craft.NormalizeOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 元素标准化实现：ParseElement → UnifiedElement 雏形（类型映射 + 全局 ID + 扩展区透传）。
 * 坐标口径由解析环节统一为左上角原点 + pt，当前直通；像素×72÷DPI 路径随 OCR 接入补充。
 *
 * @author cxxl
 */
@Component
public class NormalizerImpl implements ElementNormalizer {

    /** 类型为空时的计数键（与"未知类型"区分） */
    private static final String BLANK_TYPE = "(blank)";

    @Override
    public NormalizeOutcome normalize(List<ParseSource> sources, AssembleContext context) {
        NormalizeOutcome outcome = new NormalizeOutcome();
        if (NullUtil.isNull(sources)) {
            return outcome;
        }
        for (ParseSource source : sources) {
            for (ParseElement element : source.getElements()) {
                UnifiedElement unified = toUnified(element, source, outcome.getUnmappedTypeCounts());
                if (NullUtil.isNotNull(unified)) {
                    outcome.getElements().add(unified);
                }
            }
        }
        return outcome;
    }

    private UnifiedElement toUnified(ParseElement element, ParseSource source,
                                     Map<String, Integer> unmappedTypeCounts) {
        String provider = source.getProvider();
        UnifiedElementType type = mapType(element.getType());
        if (NullUtil.isNull(type)) {
            // 类型集合不匹配：跳过并计数（由管线落质量告警，避免静默丢元素）
            unmappedTypeCounts.merge(StrUtil.blankToDefault(element.getType(), BLANK_TYPE), 1, Integer::sum);
            return null;
        }
        UnifiedElement unified = new UnifiedElement();
        unified.setType(type.name());
        unified.setText(element.getText());
        unified.setAssetRef(element.getAssetRef());
        unified.setPage(element.getPage());
        unified.setBbox(element.getBbox());
        unified.setFont(element.getFont());
        unified.setRows(element.getRows());
        unified.setCols(element.getCols());
        unified.setHeaderRow(element.getHeaderRow());
        // 单元格行列/合并/表头标记透传（切片环节表头随片与行组依赖）
        unified.setRow(element.getRow());
        unified.setCol(element.getCol());
        unified.setRowSpan(element.getRowSpan());
        unified.setColSpan(element.getColSpan());
        unified.setIsHeader(element.getIsHeader());
        unified.setCaption(null);

        // 单元格嵌套（TABLE 的子元素）
        if (NullUtil.isNotNull(element.getCells())) {
            List<UnifiedElement> cells = new ArrayList<>();
            for (ParseElement cell : element.getCells()) {
                UnifiedElement cellUnified = toUnified(cell, source, unmappedTypeCounts);
                if (NullUtil.isNotNull(cellUnified)) {
                    cells.add(cellUnified);
                }
            }
            unified.setCells(cells);
        }

        // 扩展区：解析器特有字段透传（下游只依赖公共字段）；source 键放来源路名，provider 键放解析器
        Map<String, Object> extension = new HashMap<>();
        extension.put(ElementExtensionKey.SOURCE.key(),
                StrUtil.blankToDefault(source.getSource(), ParseSourceType.NATIVE.value()));
        if (StrUtil.isNotBlank(provider)) {
            extension.put(ElementExtensionKey.PROVIDER.key(), provider);
        }
        if (StrUtil.isNotBlank(element.getStyle())) {
            extension.put(ElementExtensionKey.STYLE.key(), element.getStyle());
        }
        if (StrUtil.isNotBlank(element.getSheetName())) {
            extension.put(ElementExtensionKey.SHEET_NAME.key(), element.getSheetName());
        }
        if (NullUtil.isNotNull(element.getNeedsOcr())) {
            extension.put(ElementExtensionKey.NEEDS_OCR.key(), element.getNeedsOcr());
        }
        if (NullUtil.isNotNull(element.getCutAtPageBottom())) {
            extension.put(ElementExtensionKey.CUT_AT_PAGE_BOTTOM.key(), element.getCutAtPageBottom());
        }
        if (NullUtil.isNotNull(element.getHeaderRepeated())) {
            extension.put(ElementExtensionKey.HEADER_REPEATED.key(), element.getHeaderRepeated());
        }
        unified.setExtension(extension);
        unified.setProvenance(element.getProvenance());

        // 目录行级特征透传（解析环节识别 → 预处理环节处置；整页聚合判定在预处理环节）
        if (Boolean.TRUE.equals(element.getTocCandidate())) {
            unified.setMarks(new java.util.ArrayList<>(List.of(ElementMark.TOC_LINE.name())));
        }

        // 全局 ID = 前缀 + 内容哈希（原文 id + 文本 + 坐标 + 来源，保证单文档内唯一）
        String seed = provider + "|" + element.getId() + "|" + StrUtil.blankToDefault(element.getText(), "")
                + "|" + (NullUtil.isNull(element.getBbox()) ? "" : element.getBbox().toString());
        unified.setId(StructureIds.of(type, seed));
        return unified;
    }

    private UnifiedElementType mapType(String parseType) {
        if (StrUtil.isBlank(parseType)) {
            return null;
        }
        try {
            return UnifiedElementType.valueOf(parseType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
