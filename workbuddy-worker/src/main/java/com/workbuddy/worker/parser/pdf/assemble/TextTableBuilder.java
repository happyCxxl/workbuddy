package com.workbuddy.worker.parser.pdf.assemble;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.TableCapabilityAttempt;
import com.workbuddy.worker.parser.pdf.detect.TableAlignmentPruner;
import com.workbuddy.worker.parser.pdf.detect.TableCandidateDetector;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.extract.PdfGeometry;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * 无边框表（文本对齐路径）：列对齐聚类（支持度过滤）成表，格内文本按列区间归属；
 * 列对齐失败或空白率过高时出 TABLE 事实并把块降级为段落。
 *
 * @author cxxl
 */
public final class TextTableBuilder {

    private TextTableBuilder() {
    }

    /** 剪枝留痕：记出被剔除的行数（疑似混入正文），表格仍按保留的行产出 */
    private static ParseFact prunedFact(Integer pageNo, int prunedRows) {
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.TABLE);
        fact.setRegion("page " + pageNo);
        fact.setEvidence("对齐图剪枝剔除 " + prunedRows + " 行（不与表格主体共享列，疑似混入正文）");
        return fact;
    }

    /**
     * 规则判不出结构时的结算：先试表格结构能力（`CapabilityRegistry.table()`），
     * 拿到可用结构就出 TABLE 元素并记补强事实；能力未给出可用结构时按内置口径出 TABLE 事实 + 区域降级为段落，并记降级事实。
     */
    private static void settleFailedTable(List<PageLine> block, PageContent page, List<ParseElement> elements,
                                          ParseSource source, String fileId,
                                          CapabilityRegistry capabilityRegistry,
                                          LineTexts.Options lineTextOptions, String evidence) {
        TableCapabilityAttempt.Outcome outcome = TableCapabilityAttempt.attempt(capabilityRegistry,
                TableRegionSupport.of(block, page, lineTextOptions), page.pageNo());
        source.getFacts().add(outcome.fact());
        if (outcome.enhanced()) {
            elements.add(outcome.table());
            source.getCapabilities().add(outcome.usage());
            return;
        }
        TableSupport.degrade(block, page, elements, source, fileId, evidence);
    }

    /** 文本路径结算：列对齐聚类成表；失败或空白率过高则降级为段落 */
    static void flush(List<PageLine> block, PageContent page, List<ParseElement> elements,
                      ParseSource source, String fileId, ParseProperties properties,
                      CapabilityRegistry capabilityRegistry) {
        if (block.size() < 2) {
            elements.add(PdfBodyAssembler.toParagraphElement(block, fileId, page));
            return;
        }
        LineTexts.Options lineTextOptions = LineTexts.Options.of(properties);
        List<List<PageLine>> rows = groupRows(block, properties.getTableRowInnerGapShare());
        List<List<Token>> rowTokens = rows.stream()
                .map(row -> row.stream().flatMap(line -> line.tokens().stream()).toList())
                .toList();
        List<Double> blockFontSizes = block.stream().map(PageLine::fontSize).toList();
        // 列聚类容差按块内最大字号折算：大字号行的 token 起点抖动更大，固定像素值会把它拆成虚列
        double columnTolerance = properties.getTableColumnToleranceRatio()
                * Math.max(blockFontSizes.stream().mapToDouble(Double::doubleValue).max().orElse(0), 1);
        int minSupportRows = Math.max(2,
                (int) Math.ceil(rows.size() * properties.getTableColumnSupportRatio()));
        List<TableCandidateDetector.Column> columns = TableCandidateDetector.columns(rowTokens, minSupportRows,
                columnTolerance);
        if (columns.size() < TableCandidateDetector.MIN_TOKENS
                || !TableCandidateDetector.enoughLinesCoverColumns(rowTokens, columns)) {
            settleFailedTable(block, page, elements, source, fileId, capabilityRegistry,
                    lineTextOptions, "列对齐聚类失败（疑似无边框/复杂表格）");
            return;
        }
        // 假表门限：正文分栏的每列被整行文字填满，表格单元格相对列宽是稀疏的
        if (!TableCandidateDetector.sparseColumns(rowTokens, columns, properties.getTableColumnMaxFillRatio())) {
            TableSupport.degrade(block, page, elements, source, fileId, "列内文本过密（疑似正文分栏）");
            return;
        }
        // 对齐图剪枝：剪掉不与表格主体共享列的行（混入的正文行），剪完不足两行按规则失败结算
        List<Integer> kept = TableAlignmentPruner.keptRows(rowTokens, columns);
        if (kept.size() < rows.size()) {
            source.getFacts().add(prunedFact(page.pageNo(), rows.size() - kept.size()));
            rows = kept.stream().map(rows::get).toList();
        }
        if (rows.size() < 2) {
            settleFailedTable(block, page, elements, source, fileId, capabilityRegistry,
                    lineTextOptions, "对齐图剪枝后不足两行（疑似正文）");
            return;
        }
        List<List<String>> rowTexts = new ArrayList<>();
        List<Double> rowFontSizes = new ArrayList<>();
        for (List<PageLine> row : rows) {
            List<String> texts = new ArrayList<>();
            for (TableCandidateDetector.Column column : columns) {
                List<String> parts = new ArrayList<>();
                for (PageLine line : row) {
                    String text = LineTexts.lineText(cellChars(line, column), lineTextOptions);
                    if (!text.isEmpty()) {
                        parts.add(text);
                    }
                }
                texts.add(String.join(" ", parts));
            }
            rowTexts.add(texts);
            rowFontSizes.add(row.stream().mapToDouble(PageLine::fontSize).average().orElse(0));
        }
        if (TableSupport.blankTable(rowTexts, properties)) {
            TableSupport.degrade(block, page, elements, source, fileId, "空白率过高（疑似框线/表单区域）");
            return;
        }
        Integer headerRow = detectHeaderRow(rowTexts, rowFontSizes, properties.getTableHeaderMaxNumericRatio());
        ParseElement table = ParseElement.of(ElementType.TABLE);
        table.setPage(page.pageNo());
        BBox bounds = PdfGeometry.boundsOf(block, PageLine::x, PageLine::y, PageLine::width, PageLine::height);
        table.setBbox(page.geometry().toViewport(bounds));
        table.setRows(rows.size());
        table.setCols(columns.size());
        table.setHeaderRow(headerRow);
        table.setCutAtPageBottom(PdfGeometry.cutAtPageBottom(bounds, page.pageHeight()));
        List<ParseElement> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<PageLine> row = rows.get(r);
            double top = row.getFirst().y();
            double bottom = row.getLast().y() + row.getLast().height();
            for (int c = 0; c < columns.size(); c++) {
                ParseElement cell = ParseElement.of(ElementType.TABLE_CELL);
                cell.setText(rowTexts.get(r).get(c));
                cell.setRow(r);
                cell.setCol(c);
                cell.setIsHeader(NullUtil.isNotNull(headerRow) && r == headerRow);
                // 单元格 bbox：列区间 × 行纵向范围（合并后的行覆盖整行高度）
                double cellX = columns.get(c).left();
                double cellWidth = columns.get(c).right() - cellX;
                cell.setBbox(page.geometry().toViewport(
                        new BBox(cellX, top, Math.max(cellWidth, 0), Math.max(bottom - top, 0))));
                cells.add(cell);
            }
        }
        table.setCells(cells);
        elements.add(table);
    }

    /**
     * 行分组：行距不超过「字号 × 比例」且 token 全部落在上一行 x 范围内的行并进上一行 ——
     * 单元格内换行的续行由此归并成一行，行数、表头判定与覆盖度都按「逻辑行」计。
     */
    private static List<List<PageLine>> groupRows(List<PageLine> block, double innerGapShare) {
        double innerGap = medianGap(block) * innerGapShare;
        List<List<PageLine>> rows = new ArrayList<>();
        for (PageLine line : block) {
            if (rows.isEmpty() || !isContinuation(rows.getLast(), line, innerGap)) {
                rows.add(new ArrayList<>(List.of(line)));
            } else {
                rows.getLast().add(line);
            }
        }
        return rows;
    }

    /** 块内相邻行间距的中位数：续行的行距明显小于它，表格行距则与之相当 */
    private static double medianGap(List<PageLine> block) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < block.size(); i++) {
            gaps.add(Math.max(block.get(i).y() - (block.get(i - 1).y() + block.get(i - 1).height()), 0));
        }
        if (gaps.isEmpty()) {
            return 0;
        }
        gaps.sort(Double::compare);
        return gaps.get(gaps.size() / 2);
    }

    private static boolean isContinuation(List<PageLine> row, PageLine line, double innerGap) {
        PageLine previous = row.getLast();
        if (line.y() - (previous.y() + previous.height()) > innerGap) {
            return false;
        }
        // 续行不越过上一行的右边界：整行宽度的下一行是新的表格行，不是续行
        if (line.x() + line.width() > previous.x() + previous.width()) {
            return false;
        }
        double left = row.stream().mapToDouble(PageLine::x).min().orElse(0);
        double right = row.stream().mapToDouble(item -> item.x() + item.width()).max().orElse(0);
        return line.tokens().stream().allMatch(token -> token.xStart() >= left && token.xEnd() <= right);
    }

    /**
     * 行内某列的字符：token 按其**中心点**归列，整个 token 的字符进同一格 ——
     * 右对齐的数字列左起点逐行不同，按字符左边界切会把金额劈到相邻两格里。
     */
    private static List<CharInfo> cellChars(PageLine line, TableCandidateDetector.Column column) {
        return line.tokens().stream()
                .filter(token -> TableCandidateDetector.contains(column, token))
                .flatMap(token -> line.chars().stream()
                        .filter(ch -> ch.x() >= token.xStart() && ch.x() <= token.xEnd()))
                .toList();
    }

    /** 表头行判定：首行含数字占比不超上限，且表体含数字（或首行字号大于表体最大字号） */
    public static Integer detectHeaderRow(List<List<String>> rowTexts, List<Double> rowFontSizes,
                                   double maxNumericRatio) {
        if (rowTexts.size() < 2) {
            return null;
        }
        if (numericRatio(rowTexts.getFirst()) > maxNumericRatio) {
            return null;
        }
        if (rowTexts.stream().skip(1).anyMatch(cells -> numericRatio(cells) > 0)) {
            return 0;
        }
        double firstSize = rowFontSizes.getFirst();
        double bodyMax = rowFontSizes.stream().skip(1).mapToDouble(Double::doubleValue).max().orElse(0);
        return firstSize > bodyMax * 1.05 ? 0 : null;
    }

    /** 行内单元格含数字的比例 */
    private static double numericRatio(List<String> cells) {
        if (cells.isEmpty()) {
            return 0;
        }
        long numeric = cells.stream()
                .filter(text -> StrUtil.isNotBlank(text) && text.matches(".*\\d.*"))
                .count();
        return (double) numeric / cells.size();
    }
}
