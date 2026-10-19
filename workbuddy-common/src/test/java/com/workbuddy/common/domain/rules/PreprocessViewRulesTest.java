package com.workbuddy.common.domain.rules;

import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预处理视图规则单测。
 *
 * <p>守护两张状态清单的口径：剔除态不进入内容流、切片跳过态 = 剔除态 + 重复份；
 * 清单错会让页眉页脚或重复内容进入切片与检索。
 */
class PreprocessViewRulesTest {

    /**
     * 剔除态清单：五种排除状态齐全。
     *
     * <p>守护 {@code EXCLUDED_STATUSES}：少一种就会把该噪声写进正文。
     */
    @Test
    void excludedStatusesShouldCoverAllNoiseKinds() {
        assertEquals(5, PreprocessViewRules.EXCLUDED_STATUSES.size());
        assertTrue(PreprocessViewRules.EXCLUDED_STATUSES.contains(
                ViewElementStatus.EXCLUDED_HEADER.name()));
        assertTrue(PreprocessViewRules.EXCLUDED_STATUSES.contains(
                ViewElementStatus.EXCLUDED_FOOTER.name()));
        assertTrue(PreprocessViewRules.EXCLUDED_STATUSES.contains(
                ViewElementStatus.EXCLUDED_TOC.name()));
        assertTrue(PreprocessViewRules.EXCLUDED_STATUSES.contains(
                ViewElementStatus.EXCLUDED_NOISE.name()));
        assertTrue(PreprocessViewRules.EXCLUDED_STATUSES.contains(
                ViewElementStatus.BACKUP_SKIPPED.name()));
    }

    /**
     * 切片跳过态：剔除态全集 + 重复份，且不含正常态。
     *
     * <p>守护 {@code CHUNK_SKIP_STATUSES} 的合并口径：漏掉重复份会重复切片。
     */
    @Test
    void chunkSkipStatusesShouldBeExcludedPlusRepeated() {
        assertEquals(6, PreprocessViewRules.CHUNK_SKIP_STATUSES.size());
        assertTrue(PreprocessViewRules.CHUNK_SKIP_STATUSES.containsAll(
                PreprocessViewRules.EXCLUDED_STATUSES));
        assertTrue(PreprocessViewRules.CHUNK_SKIP_STATUSES.contains(
                ViewElementStatus.REPEATED.name()));
        assertTrue(!PreprocessViewRules.CHUNK_SKIP_STATUSES.contains(
                ViewElementStatus.NORMAL.name()));
    }
}
