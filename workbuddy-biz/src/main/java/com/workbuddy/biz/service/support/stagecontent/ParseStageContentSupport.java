package com.workbuddy.biz.service.support.stagecontent;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.stagecontent.BBoxVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.common.convert.knowledge.StructureConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.workbuddy.biz.service.support.artifact.StageArtifactReader;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析环节内容：sources 平铺元素，顺序号从 1 起，extra 带来源/页网格/边界框/Office 锚点/表格单元格。
 */
@Component
@RequiredArgsConstructor
public class ParseStageContentSupport {

    private final StructureConverter knowledgeConverter;

    /**
     * 解析产物 → 内容条目。
     *
     * @param fileStorage 对象存储
     * @param ref         解析产物对象位置
     * @return 内容条目；产物缺失或读不到时为空列表
     */
    public List<StageContentItemVO> items(FileStorage fileStorage, ObjectRef ref) {
        ParseResult result = StageArtifactReader.read(fileStorage, ref, ParseResult.class);
        List<StageContentItemVO> items = new ArrayList<>();
        if (NullUtil.isNull(result) || NullUtil.isNull(result.getSources())) {
            return items;
        }
        int seq = 1;
        for (ParseSource source : result.getSources()) {
            for (ParseElement element : ObjectUtil.defaultIfNull(source.getElements(), List.<ParseElement>of())) {
                StageContentItemVO item = new StageContentItemVO();
                item.setAlignKey(element.getId());
                item.setSeq(seq++);
                item.setType(element.getType());
                item.setDisplay(element.getText());
                item.setExtra(parseExtra(source, element));
                items.add(item);
            }
        }
        return items;
    }

    /** 解析环节专属字段 + 公共页网格字段 + 原文定位锚点 + 表格单元格明细 */
    private StageContentExtraVO parseExtra(ParseSource source, ParseElement element) {
        StageContentExtraVO extra = new StageContentExtraVO();
        extra.setSource(source.getSource());
        extra.setProvider(source.getProvider());
        knowledgeConverter.putPageGrid(extra, element.getPage(), element.getRows(), element.getCols());
        knowledgeConverter.putBbox(extra, element.getBbox());
        if (NullUtil.isNotNull(element.getDisplayBBox())) {
            BBoxVO display = new BBoxVO();
            display.setX(element.getDisplayBBox().getX());
            display.setY(element.getDisplayBBox().getY());
            display.setWidth(element.getDisplayBBox().getWidth());
            display.setHeight(element.getDisplayBBox().getHeight());
            extra.setDisplayBBox(display);
        }
        if (NullUtil.isNotNull(element.getFont())) {
            extra.setFont(StrUtil.blankToDefault(element.getFont().getName(), "?")
                    + " " + element.getFont().getSize() + "pt"
                    + (Boolean.TRUE.equals(element.getFont().getBold()) ? " bold" : ""));
        }
        extra.setTocCandidate(element.getTocCandidate());
        if (NullUtil.isNotNull(element.getProvenance())) {
            extra.setProvenance(element.getProvenance().getPath());
        }
        extra.setDegraded(element.getDegraded());
        extra.setDegradeReason(element.getDegradeReason());
        putOfficeAnchor(extra, element);
        // 表格单元格明细（TABLE 元素内嵌）：解析详情按行列渲染网格，单元格与文本原样下发、不做截断
        if (NullUtil.isNotNull(element.getCells()) && !element.getCells().isEmpty()) {
            extra.setCells(JsonUtil.toJsonStr(element.getCells()));
        }
        return extra;
    }

    /**
     * Office 原文定位锚点：原文预览按这些字段把元素对到渲染结果上。
     *
     * <p>Word 段落给样式名（渲染器把样式名写在段落 DOM 上）；Excel 给工作表名与单元格行列
     * （行列是解析产物里的 0 基下标，与表格区域高亮同一口径）。
     */
    private void putOfficeAnchor(StageContentExtraVO extra, ParseElement element) {
        if (StrUtil.isNotBlank(element.getStyle())) {
            extra.setStyle(element.getStyle());
        }
        if (StrUtil.isNotBlank(element.getSheetName())) {
            extra.setSheetName(element.getSheetName());
        }
        if (NullUtil.isNotNull(element.getRow())) {
            extra.setRow(element.getRow());
        }
        if (NullUtil.isNotNull(element.getCol())) {
            extra.setCol(element.getCol());
        }
        if (NullUtil.isNotNull(element.getRowSpan())) {
            extra.setRowSpan(element.getRowSpan());
        }
        if (NullUtil.isNotNull(element.getColSpan())) {
            extra.setColSpan(element.getColSpan());
        }
        if (NullUtil.isNotNull(element.getIsHeader())) {
            extra.setIsHeader(element.getIsHeader());
        }
    }
}
