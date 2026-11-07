package com.workbuddy.worker.structure;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组装阈值配置校验：页眉页脚区域越界在启动期失败，合法取值放行。
 */
class StructurePropertiesValidationTest {

    /** 页眉页脚区域之和达到 1 启动失败（{@code headerFooterOverlapShouldFail}）。 */
    @Test
    void headerFooterOverlapShouldFail() {
        StructureProperties properties = PropertiesFixtures.structure();
        properties.setHeaderAreaRatio(0.6);
        properties.setFooterAreaRatio(0.6);

        IllegalStateException error = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(error.getMessage().contains("header-area-ratio"), () -> "message=" + error.getMessage());
    }

    /** 基线配置（配置面取值）应通过校验（{@code shippedBaselineShouldPass}）。 */
    @Test
    void shippedBaselineShouldPass() {
        StructureProperties properties = PropertiesFixtures.structure();

        assertDoesNotThrow(properties::validate);
    }
}
