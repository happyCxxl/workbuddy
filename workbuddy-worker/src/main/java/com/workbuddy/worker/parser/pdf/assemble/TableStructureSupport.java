package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.capability.TableCell;
import com.workbuddy.common.domain.parse.capability.TableResult;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.utils.NullUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构识别结果 → 表格元素：按结果的行列、每格 row/col span、bbox 与置信度组装 `TABLE` 与 `TABLE_CELL`。
 * 表头行取「表头格最多的行」（模型给了 `isHeader` 用模型结论）；坐标与解析器同口径（pt、左上原点），
 * 请求里带的区域 bbox 与响应里的格 bbox 在同一个坐标系。元素 ID 与表格溯源由页内后处理补。
 */
public final class TableStructureSupport {

    private TableStructureSupport() {
    }

    /**
     * 结果转表格元素。
     *
     * @param result 结构识别结果
     * @param pageNo 页码
     * @return 表格元素；结果为 null、行列缺失或没有格子时返回 null（走内置降级）
     */
    public static ParseElement toTableElement(TableResult result, int pageNo) {
        if (NullUtil.isNull(result) || NullUtil.isNull(result.getRows()) || NullUtil.isNull(result.getCols())
                || NullUtil.isNull(result.getCells()) || result.getCells().isEmpty()) {
            return null;
        }
        ParseElement table = ParseElement.of(ElementType.TABLE);
        table.setPage(pageNo);
        table.setRows(result.getRows());
        table.setCols(result.getCols());
        table.setHeaderRow(headerRow(result.getCells()));
        List<ParseElement> cells = new ArrayList<>();
        for (TableCell cell : result.getCells()) {
            ParseElement element = ParseElement.of(ElementType.TABLE_CELL);
            element.setText(cell.getText());
            element.setRow(cell.getRow());
            element.setCol(cell.getCol());
            if (isMultiSpan(cell.getRowSpan())) {
                element.setRowSpan(cell.getRowSpan());
            }
            if (isMultiSpan(cell.getColSpan())) {
                element.setColSpan(cell.getColSpan());
            }
            element.setIsHeader(cell.getIsHeader());
            element.setConfidence(cell.getConfidence());
            element.setBbox(cell.getBbox());
            cells.add(element);
        }
        table.setCells(cells);
        table.setBbox(boundsOf(cells));
        return table;
    }

    /** 表头行：表头格最多且至少有一个表头格的行号；模型没给表头标记时返回 null */
    private static Integer headerRow(List<TableCell> cells) {
        Map<Integer, Integer> headerCounts = new LinkedHashMap<>();
        for (TableCell cell : cells) {
            if (Boolean.TRUE.equals(cell.getIsHeader()) && NullUtil.isNotNull(cell.getRow())) {
                headerCounts.merge(cell.getRow(), 1, Integer::sum);
            }
        }
        return headerCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 跨度只写大于 1 的值（与规则路径口径一致：单跨不占字段） */
    private static boolean isMultiSpan(Integer span) {
        return NullUtil.isNotNull(span) && span > 1;
    }

    /** 表格包围盒：各格包围盒的并集；没有可用盒子时返回 null */
    private static BBox boundsOf(List<ParseElement> cells) {
        List<BBox> boxes = cells.stream().map(ParseElement::getBbox).filter(NullUtil::isNotNull).toList();
        if (boxes.isEmpty()) {
            return null;
        }
        double left = boxes.stream().mapToDouble(BBox::getX).min().orElse(0);
        double top = boxes.stream().mapToDouble(BBox::getY).min().orElse(0);
        double right = boxes.stream().mapToDouble(box -> box.getX() + box.getWidth()).max().orElse(0);
        double bottom = boxes.stream().mapToDouble(box -> box.getY() + box.getHeight()).max().orElse(0);
        return new BBox(left, top, right - left, bottom - top);
    }
}
