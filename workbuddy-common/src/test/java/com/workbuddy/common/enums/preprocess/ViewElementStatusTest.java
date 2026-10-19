package com.workbuddy.common.enums.preprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视图元素状态单测。
 *
 * <p>守护状态词汇表的完整性：状态随预处理产物落库并驱动切片筛选
 * （剔除类不进检索内容流），缺一类即处置结果无处表达。
 */
class ViewElementStatusTest {

    /**
     * 每条处置规则的「保留 / 标注 / 剔除」状态齐备（页眉页脚、目录、重复、噪声）。
     *
     * <p>守护 {@code ViewElementStatus} 的三态处置词汇：某规则缺任一态即失败（该处置结果无处安放）。
     */
    @Test
    void disposeStatusesShouldCoverEveryRule() {
        String[][] families = {
                {"MARKED_HEADER", "EXCLUDED_HEADER"},
                {"MARKED_TOC", "EXCLUDED_TOC"},
                {"MARKED_REPEAT", "REPEATED"},
                {"NOISE", "EXCLUDED_NOISE"},
        };
        for (String[] family : families) {
            for (String status : family) {
                assertNotNull(ViewElementStatus.valueOf(status), status + " 状态缺失");
            }
        }
        assertNotNull(ViewElementStatus.NORMAL);
    }

    /**
     * 图片与备份的辅助状态齐备。
     *
     * <p>守护 {@code ViewElementStatus} 的辅助词汇：缺任一即失败（相应标记丢失）。
     */
    @Test
    void auxiliaryStatusesShouldExist() {
        assertNotNull(ViewElementStatus.IMAGE_REF_ONLY);
        assertNotNull(ViewElementStatus.BACKUP_SKIPPED);
        assertTrue(ViewElementStatus.values().length > 0);
    }
}
