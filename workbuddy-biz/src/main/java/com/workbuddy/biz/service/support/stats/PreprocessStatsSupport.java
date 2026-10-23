package com.workbuddy.biz.service.support.stats;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.rules.PreprocessViewRules;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.preprocess.PreprocessStatsVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessSummaryVO;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * 预处理产物统计汇总：读派生视图产物 → 保留/剔除/仅标记/字段/改写计数与分布 → 摘要文案。
 *
 * <p>纯读取，不落库、不改产物；执行树的预处理节点与预处理详情共用同一份口径。
 * 统计字段含义见 {@link PreprocessStatsVO}。
 */
public final class PreprocessStatsSupport {


    /** 仅标记状态：页眉页脚/目录/噪声/重复份被标注，内容仍在检索流里 */
    public static final List<String> MARKED_STATUSES = List.of(
            ViewElementStatus.MARKED_HEADER.name(),
            ViewElementStatus.MARKED_FOOTER.name(),
            ViewElementStatus.MARKED_TOC.name(),
            ViewElementStatus.NOISE.name(),
            ViewElementStatus.MARKED_REPEAT.name());

    /** 文本改写两条规则的规则名前缀（产物轨迹里的 rule 取值） */
    private static final String RULE_ENCODING_PREFIX = "encoding-";
    private static final String RULE_TIDY_PREFIX = "text-tidy";

    /** 保留占比的取整底数 */
    private static final int PERCENT_BASE = 100;

    private PreprocessStatsSupport() {
    }

    /**
     * 读派生视图产物；对象位置为空、对象读不到或 JSON 解析失败都返回 null。
     *
     * @param fileStorage 对象存储
     * @param ref         产物对象位置（含存储类型与桶名），可空
     * @return 派生视图；取不到返回 null
     */
    public static PreprocessView readView(FileStorage fileStorage, ObjectRef ref) {
        return StatsSupport.readArtifact(fileStorage, ref, PreprocessView.class);
    }

    /**
     * 派生视图 → 统计。
     *
     * @param startedAt  运行开始时间（提供耗时），可空
     * @param finishedAt 运行结束时间（提供耗时），可空
     * @param view       该次运行的派生视图，可空
     * @return 统计；产物为空时返回 null（不陈述结论）
     */
    public static PreprocessStatsVO stats(LocalDateTime startedAt, LocalDateTime finishedAt,
                                          PreprocessView view) {
        if (NullUtil.isNull(view)) {
            return null;
        }
        List<ViewElement> elements = ObjectUtil.defaultIfNull(view.getElements(), List.of());
        int excluded = countStatuses(elements, PreprocessViewRules.EXCLUDED_STATUSES);
        int repeated = countStatuses(elements, List.of(ViewElementStatus.REPEATED.name()));
        int skipped = excluded + repeated;

        PreprocessStatsVO stats = new PreprocessStatsVO();
        stats.setElementCount(elements.size());
        stats.setRetainedCount(elements.size() - skipped);
        stats.setRetentionPercent(percent(elements.size() - skipped, elements.size()));
        stats.setExcludedCount(excluded);
        stats.setRepeatedCount(repeated);
        stats.setChunkSkippedCount(skipped);
        stats.setMarkedCount(countStatuses(elements, MARKED_STATUSES));
        stats.setFieldCount(countFields(elements));
        stats.setChangedCount(countChanged(elements));
        stats.setEncodingCount(countByRulePrefix(elements, RULE_ENCODING_PREFIX));
        stats.setTidyCount(countByRulePrefix(elements, RULE_TIDY_PREFIX));
        stats.setStatusCounts(statusCounts(elements));
        stats.setFieldTypeCounts(fieldTypeCounts(elements));
        stats.setDurationMs(StatsSupport.durationMs(startedAt, finishedAt));
        putAffectedRange(stats, elements);
        return stats;
    }

    /**
     * 预处理结论文案：按统计陈述"标记了多少、剔除了多少"，正常项不陈述；
     * 空视图给原因；失败取失败原因；统计缺失（产物不可读）时返回 null。
     *
     * @param errorMsg 失败原因
     * @param stats    预处理统计（**可空**：产物不可读时为 null）
     * @param status   任务状态
     * @return 结论文案；统计缺失或无可陈述内容时返回 null
     */
    public static String summary(String errorMsg, PreprocessStatsVO stats, String status) {
        return StatsSupport.summaryOf(errorMsg, status, "预处理失败", () -> viewSummaryBody(stats));
    }

    /** 视图统计陈述：统计缺失时不陈述结论；空视图与各异常项逐条陈述 */
    private static String viewSummaryBody(PreprocessStatsVO stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        if (n(stats.getElementCount()) == 0) {
            return "无任何可处理的元素（空视图）";
        }
        List<String> notes = new ArrayList<>();
        int marked = n(stats.getMarkedCount());
        if (marked > 0) {
            notes.add(marked + " 项仅标记、仍在检索内容流");
        }
        int excluded = n(stats.getExcludedCount());
        if (excluded > 0) {
            notes.add(excluded + " 项已剔除出检索内容流");
        }
        int repeated = n(stats.getRepeatedCount());
        if (repeated > 0) {
            notes.add("重复份 " + repeated + " 已剔除");
        }
        if (notes.isEmpty()) {
            return "未剔除内容，全部进入切片";
        }
        return String.join("；", notes);
    }

    /**
     * 指定状态的元素数。
     *
     * @param elements 元素列表
     * @param statuses 状态集合
     * @return 命中状态的元素数
     */
    public static int countStatuses(List<ViewElement> elements, List<String> statuses) {
        return (int) elements.stream()
                .filter(element -> statuses.contains(element.getStatus()))
                .count();
    }

    /**
     * 预处理详情统计（展示口径：检索文本与展示文本不同的元素数等；与步骤日志的规则改写计数口径不同）。
     *
     * @param view 预处理视图
     * @return 统计视图
     */
    public static PreprocessSummaryVO summaryOf(PreprocessView view) {
        List<ViewElement> elements = ObjectUtil.defaultIfNull(view.getElements(), List.<ViewElement>of());
        int excludedCount = countStatuses(elements, PreprocessViewRules.EXCLUDED_STATUSES);
        int repeatedCount = countStatuses(elements, List.of(ViewElementStatus.REPEATED.name()));
        PreprocessSummaryVO summary = new PreprocessSummaryVO();
        summary.setElementCount(elements.size());
        summary.setChangedCount(countChanged(elements));
        summary.setExcludedCount(excludedCount);
        summary.setRepeatedCount(repeatedCount);
        summary.setMarkedCount(countStatuses(elements, MARKED_STATUSES));
        summary.setChunkSkippedCount(excludedCount + repeatedCount);
        summary.setFieldCount(countFields(elements));
        summary.setStatusCounts(statusCounts(elements));
        return summary;
    }


    /**
     * 按状态分布（状态名到元素数；缺失状态归入 UNKNOWN）。
     *
     * @param elements 元素列表
     * @return 状态名到元素数的映射
     */
    public static Map<String, Integer> statusCounts(List<ViewElement> elements) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ViewElement element : elements) {
            String status = StrUtil.blankToDefault(element.getStatus(), "UNKNOWN");
            counts.merge(status, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 按字段类型分布（字段类型名到字段数）。
     *
     * @param elements 元素列表
     * @return 字段类型名到字段数的映射
     */
    public static Map<String, Integer> fieldTypeCounts(List<ViewElement> elements) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ViewElement element : elements) {
            for (NormalizedField field : ObjectUtil.defaultIfNull(element.getNormalizedFields(),
                    List.<NormalizedField>of())) {
                counts.merge(StrUtil.blankToDefault(field.getField(), "UNKNOWN"), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * 标准化字段总数。
     *
     * @param elements 元素列表
     * @return 字段总数
     */
    public static int countFields(List<ViewElement> elements) {
        return elements.stream()
                .mapToInt(element -> ObjectUtil.defaultIfNull(element.getNormalizedFields(), List.of()).size())
                .sum();
    }

    /**
     * 文本改写元素数：检索文本非空且与展示文本不同（与展示统计同一口径）。
     *
     * @param elements 元素列表
     * @return 改写过检索文本的元素数
     */
    public static int countChanged(List<ViewElement> elements) {
        return (int) elements.stream()
                .filter(element -> StrUtil.isNotBlank(element.getNormalizedText())
                        && !StrUtil.equals(element.getNormalizedText(), element.getDisplayText()))
                .count();
    }

    /** 轨迹里命中指定规则名前缀的元素数（一个元素命中多档只计一次） */
    private static int countByRulePrefix(List<ViewElement> elements, String rulePrefix) {
        return (int) elements.stream()
                .filter(element -> hitRule(element, rulePrefix))
                .count();
    }

    /** 元素的处理轨迹里是否命中过该规则名前缀 */
    private static boolean hitRule(ViewElement element, String rulePrefix) {
        for (TraceEntry entry : ObjectUtil.defaultIfNull(element.getPreprocessTrace(), List.<TraceEntry>of())) {
            if (StrUtil.isNotBlank(entry.getRule()) && entry.getRule().startsWith(rulePrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 受影响页范围：被处置（状态非 NORMAL）或文本有改写 / 提字段的元素所在页的始末页。
     *
     * <p>一个都取不到时不下发这两个字段（前端按"无页码范围"展示）。
     */
    private static void putAffectedRange(PreprocessStatsVO stats, List<ViewElement> elements) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (ViewElement element : elements) {
            if (!affected(element) || NullUtil.isNull(element.getPage())) {
                continue;
            }
            min = Math.min(min, element.getPage());
            max = Math.max(max, element.getPage());
        }
        if (min <= max) {
            stats.setPageFrom(min);
            stats.setPageTo(max);
        }
    }

    /** 元素是否被处置过（状态非 NORMAL、文本改写或提过字段） */
    private static boolean affected(ViewElement element) {
        return !ViewElementStatus.NORMAL.name().equals(element.getStatus())
                || (StrUtil.isNotBlank(element.getNormalizedText())
                && !StrUtil.equals(element.getNormalizedText(), element.getDisplayText()))
                || !ObjectUtil.defaultIfNull(element.getNormalizedFields(), List.of()).isEmpty();
    }

    /** 百分比（四舍五入取整）；分母非正记 0 */
    private static int percent(int part, int total) {
        return total <= 0 ? 0 : (int) Math.round(part * (double) PERCENT_BASE / total);
    }

    /** 计数取整：null 记 0（摘要文案里"多少项"按 0 陈述） */
    private static int n(Integer value) {
        return NullUtil.isNull(value) ? 0 : value;
    }
}
