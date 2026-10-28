package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.capability.LayoutRegion;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模型阅读顺序落到页面行：按行中心落在哪个模型区域给出行序（区域顺序取模型给的 order，
 * 区域未在 order 里出现时排在末尾），落在所有区域之外的行保持原有几何相对次序并排在末尾。
 * 坐标口径与请求一致（pt、左上原点）。
 */
public final class LayoutOrderSupport {

    private LayoutOrderSupport() {
    }

    /**
     * 按模型区域重排页面行。
     *
     * @param lines   几何顺序的页面行
     * @param regions 模型区域清单（索引即区域序号）
     * @param order   模型阅读顺序（区域序号序列）
     * @return 重排后的行；任一入参为空或顺序覆盖不到区域时按原顺序返回
     */
    public static List<PageLine> reorder(List<PageLine> lines, List<LayoutRegion> regions, List<Integer> order) {
        if (lines.isEmpty() || regions.isEmpty() || order.isEmpty()) {
            return lines;
        }
        List<PageLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingInt(line -> rankOf(line, regions, order)));
        return sorted;
    }

    /** 行序键：命中区域取该区域在模型顺序里的位次；未命中区域排到所有命中行之后 */
    private static int rankOf(PageLine line, List<LayoutRegion> regions, List<Integer> order) {
        double centerX = line.x() + line.width() / 2;
        double centerY = line.y() + line.height() / 2;
        for (int index = 0; index < order.size(); index++) {
            Integer regionIndex = order.get(index);
            if (NullUtil.isNull(regionIndex) || regionIndex < 0 || regionIndex >= regions.size()) {
                continue;
            }
            BBox bbox = regions.get(regionIndex).getBbox();
            if (contains(bbox, centerX, centerY)) {
                return index;
            }
        }
        return order.size();
    }

    /** 点是否落在包围盒内（左闭右开，避免相邻区域重复命中） */
    private static boolean contains(BBox bbox, double x, double y) {
        return NullUtil.isNotNull(bbox) && x >= bbox.getX() && x < bbox.getX() + bbox.getWidth()
                && y >= bbox.getY() && y < bbox.getY() + bbox.getHeight();
    }
}
