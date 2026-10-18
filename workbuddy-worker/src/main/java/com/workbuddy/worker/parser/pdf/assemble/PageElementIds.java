package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.utils.NullUtil;

import java.util.List;

/**
 * 页内元素 ID 分配点（PDF 产物后处理）：`类型前缀 + 页码 + 页内序号 + 位置`，
 * 段落如 `p1_3_72_700`、表如 `t1_4_72_320`（位置取 bbox 左上角的整 pt）；序号按元素产出顺序递增，
 * ID 单页内唯一且同一输入可复算。表格单元格挂在表 ID 下（`t1_4_72_320_c0_0`），
 * 同页多表的单元格不重号。
 */
public final class PageElementIds {

    /** 无位置 / 非本路径产出的类型前缀（唯一性由页内序号保证） */
    private static final String UNKNOWN_PREFIX = "e";

    private PageElementIds() {
    }

    /**
     * 给一页的元素补 ID：按列表顺序编号，表格的单元格随表 ID 一并编号。
     *
     * @param pageElements 本页产出的元素（原地写 {@code id}）
     * @param pageNo       页码
     */
    public static void assign(List<ParseElement> pageElements, int pageNo) {
        int seq = 0;
        for (ParseElement element : pageElements) {
            String id = prefixOf(element.getType()) + pageNo + "_" + seq++ + "_"
                    + position(element, true) + "_" + position(element, false);
            element.setId(id);
            assignCells(element, id);
        }
    }

    /** 单元格 ID：表 ID + 行列号（取格左上角行列，故一张表内唯一） */
    private static void assignCells(ParseElement element, String tableId) {
        if (NullUtil.isNull(element.getCells())) {
            return;
        }
        for (ParseElement cell : element.getCells()) {
            cell.setId(tableId + "_c" + cell.getRow() + "_" + cell.getCol());
        }
    }

    /** 位置分量：bbox 左上角取整 pt（横向取 x、纵向取 y），无 bbox 记 0 */
    private static long position(ParseElement element, boolean horizontal) {
        BBox bbox = element.getBbox();
        if (NullUtil.isNull(bbox)) {
            return 0L;
        }
        return Math.round(horizontal ? bbox.getX() : bbox.getY());
    }

    /** 类型前缀：p 段落 / t 表格 / c 单元格 / i 图片 / h 页眉 / f 页脚；其余类型记 e */
    private static String prefixOf(String type) {
        return switch (ElementType.valueOf(type)) {
            case PARAGRAPH -> "p";
            case TABLE -> "t";
            case TABLE_CELL -> "c";
            case IMAGE -> "i";
            case HEADER -> "h";
            case FOOTER -> "f";
            default -> UNKNOWN_PREFIX;
        };
    }
}
