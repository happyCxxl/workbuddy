package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.utils.NullUtil;

import java.util.List;

/**
 * 页内表格溯源分配点（PDF 产物后处理）：表元素定位路径为 `pdf#page(n)/table[i]`
 * （i 为页内表序号，从 0 起按产出顺序），单元格路径挂在表路径下（`.../table[i]/cell[r,c]`）。
 * 同页多表的溯源路径互不重合。
 */
public final class PageTableProvenance {

    private PageTableProvenance() {
    }

    /**
     * 给一页的表元素与单元格补定位路径。
     *
     * @param pageElements 本页产出的元素（原地写 {@code provenance}）
     * @param fileId       文件 ID
     * @param pageNo       页码
     */
    public static void assign(List<ParseElement> pageElements, String fileId, int pageNo) {
        int index = 0;
        for (ParseElement element : pageElements) {
            if (!ElementType.TABLE.name().equals(element.getType())) {
                continue;
            }
            String path = "pdf#page(" + pageNo + ")/table[" + index + "]";
            element.setProvenance(new Provenance(fileId, path));
            assignCells(element, fileId, path);
            index++;
        }
    }

    /** 单元格路径：表路径 + 行列号（取格左上角行列） */
    private static void assignCells(ParseElement table, String fileId, String tablePath) {
        if (NullUtil.isNull(table.getCells())) {
            return;
        }
        for (ParseElement cell : table.getCells()) {
            cell.setProvenance(new Provenance(fileId,
                    tablePath + "/cell[" + cell.getRow() + "," + cell.getCol() + "]"));
        }
    }
}
