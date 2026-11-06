package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Region;

import java.util.List;

/**
 * 行与线框区域的归属判定：行中心落在区域内即归属。
 */
public final class PageRegions {

    private PageRegions() {
    }

    /**
     * 行归属的线框区域。
     *
     * @param regions 线框区域
     * @param line 页面行
     * @return 归属区域；无归属返回 null
     */
    public static Region regionOf(List<Region> regions, PageLine line) {
        double centerX = line.x() + line.width() / 2;
        double centerY = line.y() + line.height() / 2;
        for (Region region : regions) {
            if (region.contains(centerX, centerY)) {
                return region;
            }
        }
        return null;
    }
}
