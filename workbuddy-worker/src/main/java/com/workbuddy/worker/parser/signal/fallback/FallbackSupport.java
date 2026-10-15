package com.workbuddy.worker.parser.signal.fallback;

import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.utils.NullUtil;

/**
 * 信号降级公共口径（package-private，各 handler 共用）：
 * region 页码解析与失败/扫描页登记。
 *
 * @author cxxl
 */
final class FallbackSupport {

    private FallbackSupport() {
    }

    /** 信号 region 解析页码（"page 3"→3）；解析不到返回 null（元素级 region 如 "paragraph[3]" 无单元号） */
    static Integer parsePage(String region) {
        try {
            String num = region.replace("page", "").trim();
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 失败页登记（参与 90% 门槛）。 */
    static void addFailedPage(QualityInfo quality, Integer page) {
        if (NullUtil.isNotNull(page)) {
            quality.getFailedPages().add(page);
        }
    }

    /** 扫描页登记（失败单元与整任务判定共用）。 */
    static void addScannedPage(QualityInfo quality, Integer page) {
        if (NullUtil.isNotNull(page)) {
            quality.getScannedPages().add(page);
        }
    }

    /** 空白页登记（从成功占比分母里剔除）。 */
    static void addBlankPage(QualityInfo quality, Integer page) {
        if (NullUtil.isNotNull(page)) {
            quality.getBlankPages().add(page);
        }
    }
}
