package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.SignalType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同页同 bbox 的重复表合并：一张物理表被多条行段/区域分别结算时，会产出多个 TABLE 元素；
 * 这里按 bbox 重合度聚合，合并行列与单元格文本，保留更完整的一张。
 */
public final class TableDeduplicator {

    /** 视为同一物理表的 bbox IoU 下限 */
    private static final double SAME_TABLE_IOU = 0.9;

    private TableDeduplicator() {
    }

    /**
     * 就地合并重复表。
     *
     * @param elements 本页元素
     * @param source   解析结果（写合并事实）
     * @param pageNo   页码
     */
    public static void mergeInPlace(List<ParseElement> elements, ParseSource source, int pageNo) {
        for (int i = 0; i < elements.size(); i++) {
            ParseElement base = elements.get(i);
            if (!ElementType.TABLE.name().equals(base.getType())) {
                continue;
            }
            List<Integer> duplicates = new ArrayList<>();
            for (int j = i + 1; j < elements.size(); j++) {
                ParseElement candidate = elements.get(j);
                if (ElementType.TABLE.name().equals(candidate.getType())
                        && sameTable(base.getBbox(), candidate.getBbox())) {
                    duplicates.add(j);
                }
            }
            if (duplicates.isEmpty()) {
                continue;
            }
            List<ParseElement> group = new ArrayList<>();
            group.add(base);
            for (int j : duplicates) {
                group.add(elements.get(j));
            }
            elements.set(i, merge(group, pageNo, source));
            for (int j = duplicates.size() - 1; j >= 0; j--) {
                elements.remove((int) duplicates.get(j));
            }
        }
    }

    /** bbox 重合度是否达到同一物理表门槛 */
    private static boolean sameTable(BBox left, BBox right) {
        if (left == null || right == null) {
            return false;
        }
        double x1 = Math.max(left.getX(), right.getX());
        double y1 = Math.max(left.getY(), right.getY());
        double x2 = Math.min(left.getX() + left.getWidth(), right.getX() + right.getWidth());
        double y2 = Math.min(left.getY() + left.getHeight(), right.getY() + right.getHeight());
        double intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        double union = left.getWidth() * left.getHeight() + right.getWidth() * right.getHeight() - intersection;
        return union > 0 && intersection / union >= SAME_TABLE_IOU;
    }

    /** 合并一组重复表 */
    private static ParseElement merge(List<ParseElement> group, int pageNo, ParseSource source) {
        ParseElement base = group.stream()
                .max((a, b) -> Integer.compare(nonEmptyCells(a), nonEmptyCells(b)))
                .orElse(group.getFirst());
        int rows = group.stream().mapToInt(e -> e.getRows() == null ? 0 : e.getRows()).max().orElse(0);
        int cols = group.stream().mapToInt(e -> e.getCols() == null ? 0 : e.getCols()).max().orElse(0);
        Map<String, List<ParseElement>> cells = new LinkedHashMap<>();
        for (ParseElement table : group) {
            if (table.getCells() == null) {
                continue;
            }
            for (ParseElement cell : table.getCells()) {
                if (cell.getRow() == null || cell.getCol() == null) {
                    continue;
                }
                cells.computeIfAbsent(cell.getRow() + ":" + cell.getCol(), key -> new ArrayList<>()).add(cell);
            }
        }
        List<ParseElement> mergedCells = new ArrayList<>();
        int conflicts = 0;
        Integer headerRow = base.getHeaderRow();
        for (Map.Entry<String, List<ParseElement>> entry : cells.entrySet()) {
            List<ParseElement> variants = entry.getValue();
            ParseElement template = variants.getFirst();
            List<String> texts = variants.stream()
                    .map(cell -> cell.getText() == null ? "" : cell.getText().trim())
                    .filter(text -> !text.isEmpty())
                    .toList();
            String text = mergeTexts(texts);
            if (texts.stream().distinct().count() > 1) {
                conflicts++;
            }
            ParseElement cell = ParseElement.of(ElementType.TABLE_CELL);
            cell.setRow(template.getRow());
            cell.setCol(template.getCol());
            cell.setText(text);
            cell.setRowSpan(template.getRowSpan());
            cell.setColSpan(template.getColSpan());
            cell.setBbox(template.getBbox());
            cell.setIsHeader(variants.stream().anyMatch(v -> Boolean.TRUE.equals(v.getIsHeader()))
                    || (headerRow != null && headerRow.equals(template.getRow())));
            mergedCells.add(cell);
        }
        base.setRows(rows);
        base.setCols(cols);
        base.setCells(mergedCells);
        if (base.getHeaderRow() == null) {
            base.setHeaderRow(group.stream().map(ParseElement::getHeaderRow)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null));
        }
        base.setCutAtPageBottom(group.stream().anyMatch(e -> Boolean.TRUE.equals(e.getCutAtPageBottom())));
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.TABLE);
        fact.setRegion("page " + pageNo);
        fact.setEvidence("合并同 bbox 重复表 " + group.size() + "→1"
                + (conflicts > 0 ? "，单元格冲突 " + conflicts + " 处" : ""));
        source.getFacts().add(fact);
        return base;
    }

    /** 多个片段按包含关系/顺序拼成一个单元格文本 */
    private static String mergeTexts(List<String> texts) {
        String merged = "";
        for (String text : texts) {
            if (text.isEmpty() || merged.contains(text)) {
                continue;
            }
            if (text.contains(merged)) {
                merged = text;
            } else if (merged.isEmpty()) {
                merged = text;
            } else {
                merged = merged + " " + text;
            }
        }
        return merged;
    }

    /** 非空单元格数 */
    private static int nonEmptyCells(ParseElement table) {
        if (table.getCells() == null) {
            return 0;
        }
        return (int) table.getCells().stream()
                .filter(cell -> cell.getText() != null && !cell.getText().isBlank())
                .count();
    }
}
