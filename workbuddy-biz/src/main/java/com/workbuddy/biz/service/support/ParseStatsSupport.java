package com.workbuddy.biz.service.support;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.lineage.LineageParseStatsVO;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.service.FileStorage;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析产物统计汇总：读产物本体 → 元素构成/问题单元/耗时 → 摘要文案。
 *
 * <p>执行树的解析节点与解析详情页共用同一份口径，两处展示不会走偏。
 * 纯读取，不落库、不改产物。
 *
 * @author cxxl
 */
public final class ParseStatsSupport {

    private ParseStatsSupport() {
    }

    /**
     * 读解析产物本体；对象位置为空、对象读不到或 JSON 解析失败都返回 null。
     *
     * @param fileStorage 对象存储
     * @param ref         产物对象位置（含存储类型与桶名），可空
     * @return 产物本体；取不到返回 null
     */
    public static ParseResult readArtifact(FileStorage fileStorage, ObjectRef ref) {
        return StatsSupport.readArtifact(fileStorage, ref, ParseResult.class);
    }

    /**
     * 解析产物本体 → 统计：元素构成按顶层元素类型归类，问题单元取清单始末，
     * 页数取解析器回填的单元数（缺失回落到文件引用的页数）。
     *
     * @param startedAt   运行开始时间（提供耗时），可空
     * @param finishedAt  运行结束时间（提供耗时），可空
     * @param parseResult 该次运行的产物本体，可空
     * @return 统计；产物为空时返回 null
     */
    public static LineageParseStatsVO stats(LocalDateTime startedAt, LocalDateTime finishedAt,
                                            ParseResult parseResult) {
        if (NullUtil.isNull(parseResult)) {
            return null;
        }
        List<ParseSource> sources = ObjectUtil.defaultIfNull(parseResult.getSources(), List.of());
        Map<String, Integer> typeCount = new HashMap<>();
        int unitCount = 0;
        for (ParseSource source : sources) {
            for (ParseElement element : ObjectUtil.defaultIfNull(source.getElements(), List.<ParseElement>of())) {
                typeCount.merge(StrUtil.blankToDefault(element.getType(), ""), 1, Integer::sum);
            }
            unitCount = Math.max(unitCount, ObjectUtil.defaultIfNull(source.getUnitCount(), 0));
        }
        // 正文口径 = PARAGRAPH + LIST + FIGURE_CAPTION；后两类是字典位，当前解析器不产出（计数恒 0），
        // 留在口径里，图注与列表接入后不必再改统计
        int body = typeCount.getOrDefault(ElementType.PARAGRAPH.name(), 0)
                + typeCount.getOrDefault(ElementType.LIST.name(), 0)
                + typeCount.getOrDefault(ElementType.FIGURE_CAPTION.name(), 0);
        int headerFooter = typeCount.getOrDefault(ElementType.HEADER.name(), 0)
                + typeCount.getOrDefault(ElementType.FOOTER.name(), 0);

        LineageParseStatsVO stats = new LineageParseStatsVO();
        stats.setPageCount(resolvePageCount(unitCount, parseResult));
        stats.setElementCount(typeCount.values().stream().mapToInt(Integer::intValue).sum());
        stats.setBodyCount(body);
        stats.setTableCount(typeCount.getOrDefault(ElementType.TABLE.name(), 0));
        stats.setImageCount(typeCount.getOrDefault(ElementType.IMAGE.name(), 0));
        stats.setHeaderFooterCount(headerFooter);

        List<Integer> failedUnits = failedUnits(parseResult.getQuality());
        stats.setFailedUnitCount(failedUnits.isEmpty() ? null : failedUnits.size());
        stats.setFailedFrom(failedUnits.isEmpty() ? null : failedUnits.getFirst());
        stats.setFailedTo(failedUnits.isEmpty() ? null : failedUnits.getLast());
        stats.setDurationMs(StatsSupport.durationMs(startedAt, finishedAt));
        return stats;
    }

    /**
     * 解析结论文案：失败取失败原因；成功与部分成功按统计里的问题单元陈述；其余状态为空。
     *
     * @param errorMsg 失败原因
     * @param stats    解析统计（**可空**：产物不可读时为 null）
     * @param status   任务状态
     * @return 结论文案；统计缺失或无可陈述内容时返回 null
     */
    public static String summary(String errorMsg, LineageParseStatsVO stats, String status) {
        return StatsSupport.summaryOf(errorMsg, status, "解析失败", () -> parseSummaryBody(stats));
    }

    /** 解析统计陈述：统计缺失（产物不可读）时不陈述结论，无异常的断言只在统计到手时成立 */
    private static String parseSummaryBody(LineageParseStatsVO stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        Integer failedUnitCount = stats.getFailedUnitCount();
        if (NullUtil.isNull(failedUnitCount) || failedUnitCount == 0) {
            return "无异常";
        }
        return failedUnitCount + " 单元未解析出内容（第 " + stats.getFailedFrom()
                + "–" + stats.getFailedTo() + "）";
    }

    /** 页数：优先解析器回填的判定单元数，缺失时取文件引用里的页数 */
    private static Integer resolvePageCount(int unitCount, ParseResult parseResult) {
        if (unitCount > 0) {
            return unitCount;
        }
        if (NullUtil.isNull(parseResult.getFile())) {
            return null;
        }
        Integer pageCount = parseResult.getFile().getPageCount();
        return ObjectUtil.defaultIfNull(pageCount, 0) > 0 ? pageCount : null;
    }

    /** 问题单元清单：去重升序，供始末单元号取值 */
    private static List<Integer> failedUnits(QualityInfo quality) {
        if (NullUtil.isNull(quality)) {
            return List.of();
        }
        return ObjectUtil.defaultIfNull(quality.getFailedPages(), List.<Integer>of()).stream()
                .filter(ObjectUtil::isNotNull)
                .distinct()
                .sorted()
                .toList();
    }
}
