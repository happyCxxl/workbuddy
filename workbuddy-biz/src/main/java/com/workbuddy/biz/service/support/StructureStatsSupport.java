package com.workbuddy.biz.service.support;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.structure.UnifiedPage;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.structure.PageMark;
import com.workbuddy.common.enums.structure.RelationType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装产物统计汇总：读统一文档产物 → 元素构成 / 层级分布 / 溯源覆盖率 → 摘要文案。
 *
 * <p>纯读取，不落库、不改产物、不进能力快照。执行树的组装节点与组装详情共用同一份口径。
 */
public final class StructureStatsSupport {

    /** 元素总数 */
    public static final String KEY_ELEMENT_COUNT = "elementCount";

    /** 标题元素数 */
    public static final String KEY_TITLE_COUNT = "titleCount";

    /** 章节层数 */
    public static final String KEY_CHAPTER_COUNT = "chapterCount";

    /** 一级标题数 */
    public static final String KEY_LEVEL_1 = "level1Count";

    /** 二级标题数 */
    public static final String KEY_LEVEL_2 = "level2Count";

    /** 三级及以下标题数 */
    public static final String KEY_LEVEL_3_PLUS = "level3PlusCount";

    /** 父子关系数（章节树） */
    public static final String KEY_PARENT_CHILD_COUNT = "parentChildCount";

    /** 疑似续表数 */
    public static final String KEY_CONTINUATION_COUNT = "continuationCount";

    /** 疑似续表数（放宽规则命中；关系 detail 标注"疑似"） */
    public static final String KEY_SUSPECTED_CONTINUATION_COUNT = "suspectedContinuationCount";

    /** 溯源齐备的元素数 */
    public static final String KEY_TRACED_COUNT = "tracedCount";

    /** 溯源覆盖率（百分比整数） */
    public static final String KEY_PROVENANCE_COVERAGE = "provenanceCoverage";

    /** 本次运行耗时（毫秒） */
    public static final String KEY_DURATION_MS = "durationMs";

    /** 无坐标元素数（阅读顺序靠"按坐标排 + 无坐标按原序追加"） */
    /** 阅读顺序口径：产物里没有"是否重排"的标记，用"无坐标元素数"表达（跨页续表保留首页框，不再计入） */
    public static final String KEY_WITHOUT_BBOX_COUNT = "withoutBboxCount";

    /** 重复段数（元素 marks = REPEATED_SEGMENT） */
    public static final String KEY_REPEATED_SEGMENT_COUNT = "repeatedSegmentCount";

    /** 重复页数（页 marks = REPEATED_PAGE） */
    public static final String KEY_REPEATED_PAGE_COUNT = "repeatedPageCount";

    /** 噪声页数（页 marks = NOISE_PAGE） */
    public static final String KEY_NOISE_PAGE_COUNT = "noisePageCount";

    /** 页总数 */
    public static final String KEY_PAGE_COUNT = "pageCount";

    /** 疑似续表涉及的起始页 */
    public static final String KEY_CONTINUATION_FROM_PAGE = "continuationFromPage";

    /** 疑似续表涉及的结束页 */
    public static final String KEY_CONTINUATION_TO_PAGE = "continuationToPage";

    /** 产物 schema 语义化版本 */
    public static final String KEY_SCHEMA_VERSION = "schemaVersion";

    /** 溯源覆盖率的"偏低"阈值（百分比）：低于它结论行提一句 */
    public static final int PROVENANCE_LOW_PERCENT = 80;

    private StructureStatsSupport() {
    }

    /**
     * 读组装产物本体（统一文档）；对象位置为空、对象读不到或 JSON 解析失败都返回 null。
     *
     * @param fileStorage 对象存储
     * @param ref         产物对象位置（含存储类型与桶名），可空
     * @return 产物本体；取不到返回 null
     */
    public static UnifiedDocument readDocument(FileStorage fileStorage, ObjectRef ref) {
        return StatsSupport.readArtifact(fileStorage, ref, UnifiedDocument.class);
    }

    /**
     * 组装产物本体 → 统计。键值含义见本类的 {@code KEY_*} 常量。
     *
     * @param startedAt 运行开始时间（提供耗时），可空
     * @param finishedAt 运行结束时间（提供耗时），可空
     * @param document  该次运行的组装产物，可空
     * @return 统计；产物为空时返回 null（不陈述结论）
     */
    public static Map<String, Object> stats(LocalDateTime startedAt, LocalDateTime finishedAt,
                                            UnifiedDocument document) {
        if (NullUtil.isNull(document)) {
            return null;
        }
        List<UnifiedElement> elements = ObjectUtil.defaultIfNull(document.getElements(), List.of());
        List<DocumentRelation> relations = ObjectUtil.defaultIfNull(document.getRelations(), List.of());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put(KEY_ELEMENT_COUNT, elements.size());

        int titleCount = 0;
        int level1 = 0;
        int level2 = 0;
        int level3Plus = 0;
        int chapterCount = 0;
        boolean hasChapter = false;
        for (UnifiedElement element : elements) {
            if (UnifiedElementType.TITLE.name().equals(element.getType())) {
                titleCount++;
                Integer level = element.getLevel();
                if (NullUtil.isNull(level) || level <= 1) {
                    chapterCount++;
                    level1++;
                } else if (level == 2) {
                    level2++;
                } else {
                    level3Plus++;
                }
            }
            if (UnifiedElementType.SECTION.name().equals(element.getType())) {
                hasChapter = true;
            }
        }
        // 章节层数：有 SECTION 节点按平台章节计，否则按一级标题计
        stats.put(KEY_TITLE_COUNT, titleCount);
        stats.put(KEY_CHAPTER_COUNT, hasChapter ? countSections(elements) : chapterCount);
        stats.put(KEY_LEVEL_1, level1);
        stats.put(KEY_LEVEL_2, level2);
        stats.put(KEY_LEVEL_3_PLUS, level3Plus);

        int parentChild = 0;
        int continuation = 0;
        int suspectedContinuation = 0;
        for (DocumentRelation relation : relations) {
            if (RelationType.PARENT_CHILD.name().equals(relation.getType())) {
                parentChild++;
            } else if (RelationType.CONTINUATION_OF.name().equals(relation.getType())) {
                continuation++;
                if (isSuspectedContinuation(relation)) {
                    suspectedContinuation++;
                }
            }
        }
        stats.put(KEY_PARENT_CHILD_COUNT, parentChild);
        stats.put(KEY_CONTINUATION_COUNT, continuation);
        stats.put(KEY_SUSPECTED_CONTINUATION_COUNT, suspectedContinuation);

        // 溯源口径：结构性节点（SECTION）不计入分母；可回溯 = 自身或子元素（表格单元格）带定位
        List<UnifiedElement> provenanceScope = elements.stream()
                .filter(element -> !isStructuralElement(element))
                .toList();
        long traced = provenanceScope.stream().filter(StructureStatsSupport::isTraced).count();
        stats.put(KEY_TRACED_COUNT, traced);
        stats.put(KEY_PROVENANCE_COVERAGE, coverage(traced, provenanceScope.size()));
        stats.put(KEY_DURATION_MS, StatsSupport.durationMs(startedAt, finishedAt));

        // 阅读顺序口径：产物里没有"是否重排"的标记，用"无坐标元素数"表达 ——
        // 有坐标的元素按坐标排、无坐标的按原序追加，两者共同决定阅读顺序
        long withoutBbox = elements.stream().filter(element -> NullUtil.isNull(element.getBbox())).count();
        stats.put(KEY_WITHOUT_BBOX_COUNT, withoutBbox);

        // 重复与噪声：从元素 marks 与页 marks 现算（标记由组装环节写入产物）
        stats.put(KEY_REPEATED_SEGMENT_COUNT, countMarks(elements, ElementMark.REPEATED_SEGMENT.name()));
        List<UnifiedPage> pages = ObjectUtil.defaultIfNull(document.getPages(), List.of());
        stats.put(KEY_REPEATED_PAGE_COUNT, countPageMarks(pages, PageMark.REPEATED_PAGE.name()));
        stats.put(KEY_NOISE_PAGE_COUNT, countPageMarks(pages, PageMark.NOISE_PAGE.name()));
        stats.put(KEY_PAGE_COUNT, pages.size());

        // 疑似续表页码范围：产物关系里 CONTINUATION_OF 的 from/to 带 `#p页码` 后缀
        putContinuationRange(stats, relations);

        // 产物 schema 语义化版本（文档信息里落库，卡片身份行展示）
        DocumentInfo info = document.getDocumentInfo();
        if (NullUtil.isNotNull(info) && StrUtil.isNotBlank(info.getSchemaVersion())) {
            stats.put(KEY_SCHEMA_VERSION, info.getSchemaVersion());
        }
        return stats;
    }

    /** 该接续关系是否属放宽规则命中的疑似接续（组装环节写在关系 detail 里） */
    private static boolean isSuspectedContinuation(DocumentRelation relation) {
        return StrUtil.isNotBlank(relation.getDetail()) && relation.getDetail().contains("疑似");
    }

    /** 元素 marks 里含指定标记的数量 */
    private static int countMarks(List<UnifiedElement> elements, String mark) {
        return (int) elements.stream()
                .filter(element -> NullUtil.isNotNull(element.getMarks()) && element.getMarks().contains(mark))
                .count();
    }

    /** 页 marks 里含指定标记的页数 */
    private static int countPageMarks(List<UnifiedPage> pages, String mark) {
        return (int) pages.stream()
                .filter(page -> NullUtil.isNotNull(page.getMarks()) && page.getMarks().contains(mark))
                .count();
    }

    /**
     * 疑似续表涉及的最小/最大页码。
     *
     * <p>关系形如 `from = 元素id#p后页`、`to = 元素id#p前页`，故按 `#p` 后缀取页码；
     * 一条都取不到时不下发该键（前端按"无页码"展示）。
     */
    private static void putContinuationRange(Map<String, Object> stats, List<DocumentRelation> relations) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (DocumentRelation relation : relations) {
            if (!RelationType.CONTINUATION_OF.name().equals(relation.getType())) {
                continue;
            }
            for (String value : new String[] { relation.getFrom(), relation.getTo() }) {
                Integer page = pageOfRelationRef(value);
                if (NullUtil.isNotNull(page)) {
                    min = Math.min(min, page);
                    max = Math.max(max, page);
                }
            }
        }
        if (min <= max) {
            stats.put(KEY_CONTINUATION_FROM_PAGE, min);
            stats.put(KEY_CONTINUATION_TO_PAGE, max);
        }
    }

    /** 从 `元素id#p页码` 取出页码；没有该后缀返回 null */
    private static Integer pageOfRelationRef(String ref) {
        if (StrUtil.isBlank(ref)) {
            return null;
        }
        int index = ref.lastIndexOf("#p");
        if (index < 0 || index + 2 >= ref.length()) {
            return null;
        }
        try {
            return Integer.valueOf(ref.substring(index + 2).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 组装结论文案（体检口径）：按统计逐项判断，正常项不陈述，异常项各说一句；
     * 空树给原因；失败取失败原因；统计缺失（产物不可读）时返回 null —— 统计没到手就不陈述结论。
     *
     * <p>阈值与判定都取 {@link #PROVENANCE_LOW_PERCENT}：溯源覆盖率低于它即提一句。
     *
     * @param errorMsg 失败原因
     * @param stats    组装统计（**可空**：产物不可读时为 null）
     * @param status   任务状态
     * @return 结论文案；统计缺失或无可陈述内容时返回 null
     */
    public static String summary(String errorMsg, Map<String, Object> stats, String status) {
        return StatsSupport.summaryOf(errorMsg, status, "组装失败", () -> structureSummaryBody(stats));
    }

    /** 组装统计陈述：统计缺失（产物不可读）时不陈述结论；空树与各项异常逐条陈述 */
    private static String structureSummaryBody(Map<String, Object> stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        if (intOf(stats.get(KEY_ELEMENT_COUNT)) == 0) {
            return "无任何可组装元素（空树）";
        }
        List<String> notes = new ArrayList<>();
        int coverage = intOf(stats.get(KEY_PROVENANCE_COVERAGE));
        if (coverage < PROVENANCE_LOW_PERCENT) {
            notes.add("溯源偏低 " + coverage + "%");
        }
        int suspectedContinuation = intOf(stats.get(KEY_SUSPECTED_CONTINUATION_COUNT));
        if (suspectedContinuation > 0) {
            notes.add("疑似续表 " + suspectedContinuation + " 处");
        }
        int repeated = intOf(stats.get(KEY_REPEATED_SEGMENT_COUNT));
        int noise = intOf(stats.get(KEY_NOISE_PAGE_COUNT));
        if (repeated > 0 || noise > 0) {
            notes.add("重复与噪声待处置");
        }
        if (notes.isEmpty()) {
            return "结构可用";
        }
        return "结构可用 · " + String.join(" · ", notes);
    }

    /** 元素是否可回溯：自身带完整溯源，或其子元素（表格单元格）带完整溯源 */
    private static boolean isTraced(UnifiedElement element) {
        if (hasProvenance(element.getProvenance())) {
            return true;
        }
        return NullUtil.isNotNull(element.getCells()) && element.getCells().stream()
                .anyMatch(cell -> hasProvenance(cell.getProvenance()));
    }

    /** 溯源是否齐备（文件引用 + 定位路径） */
    private static boolean hasProvenance(Provenance provenance) {
        return NullUtil.isNotNull(provenance)
                && StrUtil.isNotBlank(provenance.getFile())
                && StrUtil.isNotBlank(provenance.getPath());
    }

    /** 结构性节点：组装环节自造的节点（SECTION），没有原文定位 */
    private static boolean isStructuralElement(UnifiedElement element) {
        return UnifiedElementType.SECTION.name().equals(element.getType());
    }

    /** 章节节点数（SECTION 类型） */
    private static int countSections(List<UnifiedElement> elements) {
        return (int) elements.stream()
                .filter(element -> UnifiedElementType.SECTION.name().equals(element.getType()))
                .count();
    }

    /** 溯源覆盖率（百分比，四舍五入取整）；无元素时记 0 */
    private static int coverage(long traced, int total) {
        return total <= 0 ? 0 : (int) Math.round(traced * 100.0 / total);
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
