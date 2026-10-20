package com.workbuddy.biz.service.support;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * 预处理环节内容：视图元素映射展示/标准化文本与状态，extra 按非空并入原文、标记、字段、单元格与轨迹。
 */
public final class PreprocessStageContentSupport {

    private PreprocessStageContentSupport() {
    }

    /**
     * 预处理视图产物 → 内容条目。
     *
     * @param fileStorage 对象存储
     * @param ref         预处理产物对象位置
     * @return 内容条目；产物缺失或读不到时为空列表
     */
    public static List<StageContentItemVO> items(FileStorage fileStorage, ObjectRef ref) {
        PreprocessView view = StageArtifactReader.read(fileStorage, ref, PreprocessView.class);
        List<StageContentItemVO> items = new ArrayList<>();
        if (NullUtil.isNull(view) || NullUtil.isNull(view.getElements())) {
            return items;
        }
        int seq = 1;
        for (ViewElement element : view.getElements()) {
            StageContentItemVO item = new StageContentItemVO();
            item.setAlignKey(element.getElementId());
            item.setSeq(seq++);
            item.setType(element.getType());
            item.setStatus(element.getStatus());
            item.setDisplay(element.getDisplayText());
            item.setNormalized(element.getNormalizedText());
            StageContentExtraVO extra = new StageContentExtraVO();
            if (StrUtil.isNotBlank(element.getRawText())) {
                extra.setRawText(element.getRawText());
            }
            if (NullUtil.isNotNull(element.getMarks()) && !element.getMarks().isEmpty()) {
                extra.setMarks(element.getMarks());
            }
            if (NullUtil.isNotNull(element.getNormalizedFields()) && !element.getNormalizedFields().isEmpty()) {
                extra.setNormalizedFields(JsonUtil.toJsonStr(element.getNormalizedFields()));
            }
            if (NullUtil.isNotNull(element.getCells()) && !element.getCells().isEmpty()) {
                extra.setCells(JsonUtil.toJsonStr(element.getCells()));
            }
            if (NullUtil.isNotNull(element.getPage())) {
                extra.setPage(element.getPage());
            }
            // 处理轨迹只对被处理过的元素下发（非 KEEP 条目），避免响应体膨胀
            List<TraceEntry> trace = nonKeepTrace(element);
            if (!trace.isEmpty()) {
                extra.setTrace(JsonUtil.toJsonStr(trace));
            }
            item.setExtra(extra);
            items.add(item);
        }
        return items;
    }

    /** 处理轨迹里的非 KEEP 条目（KEEP = 未命中改写，下发没有意义） */
    private static List<TraceEntry> nonKeepTrace(ViewElement element) {
        return ObjectUtil.defaultIfNull(element.getPreprocessTrace(), List.<TraceEntry>of()).stream()
                .filter(entry -> !TraceEntry.ACTION_KEEP.equals(entry.getAction()))
                .toList();
    }
}
