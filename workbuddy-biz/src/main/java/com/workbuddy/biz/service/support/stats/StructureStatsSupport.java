package com.workbuddy.biz.service.support.stats;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.domain.parse.QualityWarning;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.ConflictRecord;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.DocumentQuality;
import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.structure.UnifiedPage;
import com.workbuddy.common.dto.response.structure.StructureStatsVO;
import com.workbuddy.common.dto.response.structure.StructureSummaryVO;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.structure.PageMark;
import com.workbuddy.common.enums.structure.RelationType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 组装产物统计汇总：读统一文档产物 → 元素构成 / 层级分布 / 溯源覆盖率 → 摘要文案。
 *
 * <p>纯读取，不落库、不改产物、不进能力快照。执行树的组装节点与组装详情共用同一份口径。
 * 统计字段含义见 {@link StructureStatsVO}。
 */
public final class StructureStatsSupport {


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
     * 组装产物本体 → 统计。
     *
     * @param startedAt 运行开始时间（提供耗时），可空
     * @param finishedAt 运行结束时间（提供耗时），可空
     * @param document  该次运行的组装产物，可空
     * @return 统计；产物为空时返回 null（不陈述结论）
     */
    public static StructureStatsVO stats(LocalDateTime startedAt, LocalDateTime finishedAt,
                                         UnifiedDocument document) {
        if (NullUtil.isNull(document)) {
            return null;
        }
        List<UnifiedElement> elements = ObjectUtil.defaultIfNull(document.getElements(), List.of());
        List<DocumentRelation> relations = ObjectUtil.defaultIfNull(document.getRelations(), List.of());

        StructureStatsVO stats = new StructureStatsVO();
        stats.setElementCount(elements.size());

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
        stats.setTitleCount(titleCount);
        stats.setChapterCount(hasChapter ? countSections(elements) : chapterCount);
        stats.setLevel1Count(level1);
        stats.setLevel2Count(level2);
        stats.setLevel3PlusCount(level3Plus);

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
        stats.setParentChildCount(parentChild);
        stats.setContinuationCount(continuation);
        stats.setSuspectedContinuationCount(suspectedContinuation);

        // 溯源口径：结构性节点（SECTION）不计入分母；可回溯 = 自身或子元素（表格单元格）带定位
        List<UnifiedElement> provenanceScope = elements.stream()
                .filter(element -> !isStructuralElement(element))
                .toList();
        long traced = provenanceScope.stream().filter(StructureStatsSupport::isTraced).count();
        stats.setTracedCount((int) traced);
        stats.setProvenanceCoverage(coverage(traced, provenanceScope.size()));
        stats.setDurationMs(StatsSupport.durationMs(startedAt, finishedAt));

        // 阅读顺序口径：产物里没有"是否重排"的标记，用"无坐标元素数"表达 ——
        // 有坐标的元素按坐标排、无坐标的按原序追加，两者共同决定阅读顺序
        long withoutBbox = elements.stream().filter(element -> NullUtil.isNull(element.getBbox())).count();
        stats.setWithoutBboxCount((int) withoutBbox);

        // 重复与噪声：从元素 marks 与页 marks 现算（标记由组装环节写入产物）
        stats.setRepeatedSegmentCount(countMarks(elements, ElementMark.REPEATED_SEGMENT.name()));
        List<UnifiedPage> pages = ObjectUtil.defaultIfNull(document.getPages(), List.of());
        stats.setRepeatedPageCount(countPageMarks(pages, PageMark.REPEATED_PAGE.name()));
        stats.setNoisePageCount(countPageMarks(pages, PageMark.NOISE_PAGE.name()));
        stats.setPageCount(pages.size());

        // 疑似续表页码范围：产物关系里 CONTINUATION_OF 的 from/to 带 `#p页码` 后缀
        putContinuationRange(stats, relations);

        // 产物 schema 语义化版本（文档信息里落库，卡片身份行展示）
        DocumentInfo info = document.getDocumentInfo();
        if (NullUtil.isNotNull(info) && StrUtil.isNotBlank(info.getSchemaVersion())) {
            stats.setSchemaVersion(info.getSchemaVersion());
        }
        return stats;
    }

    /**
     * 组装详情统计：元素按类型计数 + 关系/冲突/告警数。
     *
     * @param document 统一文档产物
     * @return 结构统计视图
     */
    public static StructureSummaryVO summaryOf(UnifiedDocument document) {
        List<UnifiedElement> elements = ObjectUtil.defaultIfNull(document.getElements(), List.<UnifiedElement>of());
        DocumentQuality quality = ObjectUtil.defaultIfNull(document.getQuality(), new DocumentQuality());
        StructureSummaryVO summary = new StructureSummaryVO();
        summary.setElementCount(elements.size());
        summary.setTitleCount(countByType(elements, UnifiedElementType.TITLE));
        summary.setParagraphCount(countByType(elements, UnifiedElementType.PARAGRAPH));
        summary.setTableCount(countByType(elements, UnifiedElementType.TABLE));
        summary.setImageCount(countByType(elements, UnifiedElementType.IMAGE));
        summary.setRelationCount(ObjectUtil.defaultIfNull(document.getRelations(), List.<DocumentRelation>of()).size());
        summary.setConflictCount(ObjectUtil.defaultIfNull(quality.getConflicts(), List.<ConflictRecord>of()).size());
        summary.setWarningCount(ObjectUtil.defaultIfNull(quality.getWarnings(), List.<QualityWarning>of()).size());
        return summary;
    }

    /**
     * 指定类型的元素数。
     *
     * @param elements 元素列表
     * @param type     元素类型
     * @return 命中类型的元素数
     */
    public static int countByType(List<UnifiedElement> elements, UnifiedElementType type) {
        return (int) elements.stream().filter(e -> type.name().equals(e.getType())).count();
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
    private static void putContinuationRange(StructureStatsVO stats, List<DocumentRelation> relations) {
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
            stats.setContinuationFromPage(min);
            stats.setContinuationToPage(max);
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
    public static String summary(String errorMsg, StructureStatsVO stats, String status) {
        return StatsSupport.summaryOf(errorMsg, status, "组装失败", () -> structureSummaryBody(stats));
    }

    /** 组装统计陈述：统计缺失（产物不可读）时不陈述结论；空树与各项异常逐条陈述 */
    private static String structureSummaryBody(StructureStatsVO stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        if (n(stats.getElementCount()) == 0) {
            return "无任何可组装元素（空树）";
        }
        List<String> notes = new ArrayList<>();
        int coverage = n(stats.getProvenanceCoverage());
        if (coverage < PROVENANCE_LOW_PERCENT) {
            notes.add("溯源偏低 " + coverage + "%");
        }
        int suspectedContinuation = n(stats.getSuspectedContinuationCount());
        if (suspectedContinuation > 0) {
            notes.add("疑似续表 " + suspectedContinuation + " 处");
        }
        int repeated = n(stats.getRepeatedSegmentCount());
        int noise = n(stats.getNoisePageCount());
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

    /** 计数取整：null 记 0（摘要文案里"多少项"按 0 陈述） */
    private static int n(Integer value) {
        return NullUtil.isNull(value) ? 0 : value;
    }
}
