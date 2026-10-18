package com.workbuddy.worker.structure.impl.craft;
import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.domain.structure.ContinuationJudgeContext;
import com.workbuddy.common.domain.structure.ContinuationJudgeResult;
import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.ElementBBox;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.structure.ElementExtensionKey;
import com.workbuddy.common.enums.structure.RelationType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.TextUtil;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.craft.ContinuationOutcome;
import com.workbuddy.worker.structure.craft.TableContinuationResolver;
import com.workbuddy.worker.structure.impl.StructureJudgeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 跨页续表接续实现（仅 PDF 表格）：
 * 主规则四条件：相邻页 + 页尾/页首 + 表头一致 + 列数相同（相邻性按当前末页比较，同一张表可链式跨任意页数）；
 * 放宽规则：列数相同 + 列宽模式一致（表头不一致/无表头）→ 疑似接续（标告警，模型兜底可介入）；
 * 第二页自带表头 = 两张独立表（主规则表头一致除外）。
 * 合并结果保留首页的 page 与 bbox，跨页位置由 pageRange/bboxes 逐页累积承载。
 */
@Component
@RequiredArgsConstructor
public class TableContinuationResolverImpl implements TableContinuationResolver {

    private static final ContinuationDecision NOT_MERGED = new ContinuationDecision(false, false);

    private static final ContinuationDecision MERGED = new ContinuationDecision(true, false);

    private static final ContinuationDecision SUSPECTED = new ContinuationDecision(true, true);

    /** 表头相似度的不可判定取值（表头行读不到时返回，低于任何有效阈值） */
    private static final double UNJUDGEABLE_SIMILARITY = -1;

    private final StructureJudgeRegistry judgeRegistry;

    /** 接续判定结果（是否合并 + 是否属放宽规则的疑似接续） */
    private record ContinuationDecision(boolean merged, boolean suspected) {
    }

    /**
     * 跨页表格续接：表头重复的下一页表格并回前表。
     *
     * @param ordered 有序元素
     * @param context 结构化上下文
     * @return 续接结果
     */
    @Override
    public ContinuationOutcome joinContinuations(List<UnifiedElement> ordered, AssembleContext context) {
        ContinuationOutcome outcome = new ContinuationOutcome();
        List<UnifiedElement> elements = new ArrayList<>(ordered);

        for (int i = 0; i < elements.size() - 1; i++) {
            // 链式推进：合并结果留在原位继续与后一条比较，同一张表可跨任意页数
            while (i + 1 < elements.size()) {
                ContinuationDecision decision = decide(elements.get(i), elements.get(i + 1), context);
                if (!decision.merged()) {
                    break;
                }
                UnifiedElement a = elements.get(i);
                UnifiedElement b = elements.get(i + 1);
                int firstPage = firstPageOf(a);
                int nextPage = b.getPage();
                mergeTables(a, b);
                outcome.getRelations().add(new DocumentRelation(RelationType.CONTINUATION_OF.name(),
                        a.getId() + "#p" + nextPage, a.getId() + "#p" + firstPage,
                        (decision.suspected() ? "疑似续表（放宽规则）" : "续表四条件命中") + "，表头继承"));
                elements.remove(i + 1);
                outcome.setContinuationCount(outcome.getContinuationCount() + 1);
                if (decision.suspected()) {
                    outcome.setSuspectedCount(outcome.getSuspectedCount() + 1);
                }
            }
        }
        outcome.setElements(elements);
        return outcome;
    }

    /** 接续判定（四条件 + 表头一致或列宽模式放宽规则）；页码相邻性按当前末页比较，表头或列数不可判定时不进主规则 */
    private ContinuationDecision decide(UnifiedElement a, UnifiedElement b, AssembleContext context) {
        if (isNotPdfTable(a) || isNotPdfTable(b) || !isCutAtPageBottom(a)) {
            return NOT_MERGED;
        }
        if (NullUtil.isNull(b.getPage()) || NullUtil.isNull(lastPageOf(a))
                || !Objects.equals(b.getPage(), lastPageOf(a) + 1)) {
            return NOT_MERGED;
        }
        if (NullUtil.isNull(b.getBbox()) || b.getBbox().getY() >= context.getProperties().getPageTopThreshold()) {
            // 下一页表格不在页首
            return NOT_MERGED;
        }
        boolean columnsKnown = NullUtil.isNotNull(a.getCols()) && NullUtil.isNotNull(b.getCols());
        if (columnsKnown && !Objects.equals(a.getCols(), b.getCols())) {
            // 列数已知且不同
            return NOT_MERGED;
        }
        // 主规则要求列数已知且表头可读且相似；任一条件不可判定即落放宽规则（按参考行列宽判定）
        if (columnsKnown && headerSimilarity(a, b) >= context.getProperties().getContinuationHeaderSimilarity()) {
            // 主规则
            return MERGED;
        }
        if (!columnWidthPatternMatch(a, b, context)) {
            return NOT_MERGED;
        }
        // 放宽规则：疑似接续；模型兜底可介入，无实现按规则接续
        var judge = judgeRegistry.active();
        if (NullUtil.isNull(judge)) {
            return SUSPECTED;
        }
        ContinuationJudgeResult result = judge.judgeContinuation(judgeContext(a, b));
        return NullUtil.isNull(result) || Boolean.TRUE.equals(result.getIsContinuation()) ? SUSPECTED : NOT_MERGED;
    }

    private void mergeTables(UnifiedElement a, UnifiedElement b) {
        // 表头继承：B 的表头行是重复表头，丢弃；其余行按保留行序号重编号后拼接到 A
        List<UnifiedElement> mergedCells = new ArrayList<>(NullUtil.isNull(a.getCells()) ? List.of() : a.getCells());
        int baseRows = baseRowsOf(a);
        int droppedRows = appendRows(mergedCells, b, baseRows);
        a.setCells(mergedCells);
        a.setRows(baseRows + declaredRowsOf(b) - droppedRows);
        // 仅在确实丢弃了续页表头行时才算表头继承
        a.setHeaderInherited(droppedRows > 0);
        // 跨页位置以列表承载：pageRange/bboxes 逐页累积；首页的 page 与 bbox 保留在元素上
        a.setPageRange(appendPageRange(a, b));
        a.setBboxes(appendBboxes(a, b));
    }

    /** A 侧既有行数：rows 缺失时按单元格行数兜底，续页行号不与既有单元格重叠 */
    private int baseRowsOf(UnifiedElement a) {
        return NullUtil.isNotNull(a.getRows()) ? a.getRows() : cellRowsOf(a);
    }

    /** 表格声明行数：rows 缺失时按单元格行数兜底 */
    private int declaredRowsOf(UnifiedElement table) {
        return NullUtil.isNotNull(table.getRows()) ? table.getRows() : cellRowsOf(table);
    }

    /** 单元格覆盖的行数（最大行号 + 1；无行号信息时为 0） */
    private int cellRowsOf(UnifiedElement table) {
        if (NullUtil.isNull(table.getCells())) {
            return 0;
        }
        return table.getCells().stream()
                .map(UnifiedElement::getRow)
                .filter(NullUtil::isNotNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
    }

    /** 拼接续页单元格：丢弃重复表头行，其余行按保留行序号重编号；返回实际丢弃的表头行数 */
    private int appendRows(List<UnifiedElement> mergedCells, UnifiedElement b, int baseRows) {
        if (NullUtil.isNull(b.getCells())) {
            return 0;
        }
        List<Integer> cellRows = b.getCells().stream()
                .map(UnifiedElement::getRow)
                .filter(NullUtil::isNotNull)
                .distinct()
                .sorted()
                .toList();
        List<Integer> keptRows = cellRows.stream().filter(row -> !isHeaderRow(b, row)).toList();
        Map<Integer, Integer> rowNumbers = new HashMap<>();
        for (int i = 0; i < keptRows.size(); i++) {
            rowNumbers.put(keptRows.get(i), baseRows + i);
        }
        for (UnifiedElement cell : b.getCells()) {
            if (NullUtil.isNotNull(cell.getRow())) {
                if (isHeaderRow(b, cell.getRow())) {
                    // 重复表头行不再拼接
                    continue;
                }
                cell.setRow(rowNumbers.get(cell.getRow()));
            }
            mergedCells.add(cell);
        }
        return cellRows.size() - keptRows.size();
    }

    /** 该行是否 B 的表头行：表头未判定（headerRow 为空）时不丢任何行 */
    private boolean isHeaderRow(UnifiedElement b, Integer row) {
        return NullUtil.isNotNull(b.getHeaderRow()) && ObjectUtil.equals(row, b.getHeaderRow());
    }

    /** 页码范围累积：首次合并写入首页，其后逐页追加（同一页不重复） */
    private List<Integer> appendPageRange(UnifiedElement a, UnifiedElement b) {
        List<Integer> range = new ArrayList<>();
        if (NullUtil.isNotNull(a.getPageRange())) {
            range.addAll(a.getPageRange());
        } else if (NullUtil.isNotNull(a.getPage())) {
            range.add(a.getPage());
        }
        if (NullUtil.isNotNull(b.getPage()) && !range.contains(b.getPage())) {
            range.add(b.getPage());
        }
        return range;
    }

    /** 分段框累积：首页框与各续页框按页序保留 */
    private List<ElementBBox> appendBboxes(UnifiedElement a, UnifiedElement b) {
        List<ElementBBox> bboxes = new ArrayList<>();
        if (NullUtil.isNotNull(a.getBboxes())) {
            bboxes.addAll(a.getBboxes());
        } else if (NullUtil.isNotNull(a.getBbox()) && NullUtil.isNotNull(a.getPage())) {
            bboxes.add(new ElementBBox(a.getPage(), a.getBbox()));
        }
        if (NullUtil.isNotNull(b.getBbox()) && NullUtil.isNotNull(b.getPage())) {
            bboxes.add(new ElementBBox(b.getPage(), b.getBbox()));
        }
        return bboxes;
    }

    /** 首页页码：合并结果的首页在 pageRange 首位，未合并时即 page 本身 */
    private Integer firstPageOf(UnifiedElement element) {
        List<Integer> pageRange = element.getPageRange();
        return NullUtil.isNotNull(pageRange) && !pageRange.isEmpty() ? pageRange.getFirst() : element.getPage();
    }

    /** 末页页码：合并结果的末页在 pageRange 末尾，未合并时即 page 本身 */
    private Integer lastPageOf(UnifiedElement element) {
        List<Integer> pageRange = element.getPageRange();
        return NullUtil.isNotNull(pageRange) && !pageRange.isEmpty() ? pageRange.getLast() : element.getPage();
    }

    /** 是否非 PDF 表格（非 TABLE 类型或无页码；接续判定只针对 PDF 表格） */
    private boolean isNotPdfTable(UnifiedElement element) {
        return !UnifiedElementType.TABLE.name().equals(element.getType())
                || !NullUtil.isNotNull(element.getPage());
    }

    private boolean isCutAtPageBottom(UnifiedElement element) {
        Object flag = NullUtil.isNull(element.getExtension()) ? null
                : element.getExtension().get(ElementExtensionKey.CUT_AT_PAGE_BOTTOM.key());
        return Boolean.TRUE.equals(flag);
    }

    /** 表头相似度：任一侧表头行不可读时返回不可判定（-1），不再把"都读不到"当成一致 */
    private double headerSimilarity(UnifiedElement a, UnifiedElement b) {
        Integer headerA = readableHeaderRow(a);
        Integer headerB = readableHeaderRow(b);
        if (NullUtil.isNull(headerA) || NullUtil.isNull(headerB)) {
            return UNJUDGEABLE_SIMILARITY;
        }
        return TextUtil.jaccardCharSet(String.join("", rowTexts(a, headerA)), String.join("", rowTexts(b, headerB)));
    }

    /** 可读表头行：headerRow 非空且该行确有单元格；读不到时返回 null（表头未判定） */
    private Integer readableHeaderRow(UnifiedElement table) {
        return rowCells(table, table.getHeaderRow()).isEmpty() ? null : table.getHeaderRow();
    }

    /** 参考行：表头行可读时用它，读不到时退化为该表首行（列宽模式判定的几何依据） */
    private Integer referenceRow(UnifiedElement table) {
        Integer headerRow = readableHeaderRow(table);
        if (NullUtil.isNotNull(headerRow)) {
            return headerRow;
        }
        if (NullUtil.isNull(table.getCells())) {
            return null;
        }
        return table.getCells().stream()
                .map(UnifiedElement::getRow)
                .filter(NullUtil::isNotNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private List<String> rowTexts(UnifiedElement table, Integer row) {
        return rowCells(table, row).stream().map(UnifiedElement::getText).toList();
    }

    /** 指定行单元格：按 row 过滤 + 按 col 排序（rowTexts/rowWidths 共用口径） */
    private List<UnifiedElement> rowCells(UnifiedElement table, Integer row) {
        if (NullUtil.isNull(table.getCells()) || NullUtil.isNull(row)) {
            return List.of();
        }
        return table.getCells().stream()
                .filter(c -> ObjectUtil.equals(c.getRow(), row))
                .sorted(Comparator.comparingInt(c -> ObjectUtil.defaultIfNull(c.getCol(), 0)))
                .toList();
    }

    /** 列宽模式：按参考行（表头不可读时用首行）比较各列宽度，命中率 ≥ 0.6 视为几何一致 */
    private boolean columnWidthPatternMatch(UnifiedElement a, UnifiedElement b, AssembleContext context) {
        List<Double> widthsA = rowWidths(a, referenceRow(a));
        List<Double> widthsB = rowWidths(b, referenceRow(b));
        if (widthsA.size() != widthsB.size() || widthsA.isEmpty()) {
            return false;
        }
        double tolerance = context.getProperties().getContinuationColumnWidthTolerance();
        int matched = 0;
        for (int i = 0; i < widthsA.size(); i++) {
            if (Math.abs(widthsA.get(i) - widthsB.get(i)) <= tolerance) {
                matched++;
            }
        }
        return (double) matched / widthsA.size() >= context.getProperties().getColumnWidthMatchRatio();
    }

    private List<Double> rowWidths(UnifiedElement table, Integer row) {
        return rowCells(table, row).stream()
                .map(c -> NullUtil.isNull(c.getBbox()) ? 0d : c.getBbox().getWidth())
                .toList();
    }

    private ContinuationJudgeContext judgeContext(UnifiedElement a, UnifiedElement b) {
        ContinuationJudgeContext context = new ContinuationJudgeContext();
        context.setTableAHeaders(rowTexts(a, a.getHeaderRow()));
        context.setTableAColumns(a.getCols());
        context.setTableBHeaders(rowTexts(b, b.getHeaderRow()));
        context.setTableBColumns(b.getCols());
        context.setPageA(a.getPage());
        context.setPageB(b.getPage());
        return context;
    }
}
