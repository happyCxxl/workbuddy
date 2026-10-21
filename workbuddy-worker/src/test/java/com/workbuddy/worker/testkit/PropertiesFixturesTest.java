package com.workbuddy.worker.testkit;

import com.workbuddy.common.enums.embed.EmbedSwitch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 配置夹具口径：夹具取的是配置面的真实取值，不是代码侧的兜底初值。
 *
 * <p>判别式用例是 `enabledFormats`：代码初值 5 种、配置面 7 种（配置面放开 PPT/PPTX），
 * 夹具读到 7 种即证明走的是配置文件而不是 `new` 出来的对象。
 */
class PropertiesFixturesTest {

    /**
     * 守护 {@code fixturesShouldReadConfigFaceNotCodeDefaults}：该场景行为改动即失败。
     */
    @Test
    void fixturesShouldReadConfigFaceNotCodeDefaults() {
        assertEquals(7, PropertiesFixtures.fileValidation().getEnabledFormats().size(),
                "配置面启用 7 种格式（代码初值 5 种）：读到 5 种说明夹具退回了代码默认值");
    }

    /**
     * 守护 {@code fixturesShouldBindShippedBaseline}：该场景行为改动即失败。
     */
    @Test
    void fixturesShouldBindShippedBaseline() {
        assertAll(
                () -> assertEquals(12.0, PropertiesFixtures.parse().getLayoutColumnStartTolerance()),
                () -> assertEquals(4.0, PropertiesFixtures.parse().getLayoutSpanningGapFactor()),
                () -> assertEquals(50, PropertiesFixtures.parse().getPageWindowSize()),
                () -> assertEquals(0.3, PropertiesFixtures.structure().getIouThreshold()),
                () -> assertEquals(80, PropertiesFixtures.structure().getProvenanceLowPercent()),
                () -> assertEquals(3, PropertiesFixtures.preprocess().getTocMinLinesPerPage()),
                () -> assertEquals(800, PropertiesFixtures.chunk().getTargetMaxLen()),
                () -> assertEquals(0, PropertiesFixtures.chunk().getStructureOverlap()),
                () -> assertEquals(32, PropertiesFixtures.embed().getBatchSize()),
                () -> assertEquals(EmbedSwitch.ON, PropertiesFixtures.embed().getCacheEnabled()),
                () -> assertEquals(EmbedSwitch.OFF, PropertiesFixtures.embed().getIncludeParent()),
                () -> assertEquals(104857600L, PropertiesFixtures.fileValidation().getMaxSize()));
    }

    /**
     * 守护 {@code fixturesShouldReturnIndependentInstances}：该场景行为改动即失败。
     */
    @Test
    void fixturesShouldReturnIndependentInstances() {
        PropertiesFixtures.parse().setPageWindowSize(7);

        assertEquals(50, PropertiesFixtures.parse().getPageWindowSize(),
                "夹具每次返回新实例，用例改字段不污染其他用例");
    }
}
