package com.workbuddy.worker.parser;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析阈值启动期校验单测：缺键、越界、跨字段与允许 0 的配置。
 */
class ParsePropertiesValidationTest {

    /**
     * 守护 {@code validConfigShouldPass}：配置面全量合法时校验通过。
     */
    @Test
    void validConfigShouldPass() {
        assertDoesNotThrow(PropertiesFixtures.parse()::validate);
    }

    /**
     * 守护 {@code missingKeysShouldFailAtStartup}：缺键绑定为 0 时启动失败。
     */
    @Test
    void missingKeysShouldFailAtStartup() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ParseProperties().validate());
        assertTrue(error.getMessage().contains("page-window-size"), () -> "message=" + error.getMessage());
    }

    /**
     * 守护 {@code ratioOutOfRangeShouldFail}：比例越界启动失败。
     */
    @Test
    void ratioOutOfRangeShouldFail() {
        ParseProperties properties = PropertiesFixtures.parse();
        properties.setSuccessUnitRatio(1.5);

        IllegalStateException error = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(error.getMessage().contains("success-unit-ratio"), () -> "message=" + error.getMessage());
    }

    /**
     * 守护 {@code zeroDenominatorShouldFail}：正分母字段为 0 启动失败。
     */
    @Test
    void zeroDenominatorShouldFail() {
        ParseProperties properties = PropertiesFixtures.parse();
        properties.setOfficePageChars(0);

        IllegalStateException error = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(error.getMessage().contains("office-page-chars"), () -> "message=" + error.getMessage());
    }

    /**
     * 守护 {@code columnMaxCountOutOfRangeShouldFail}：栏数越界启动失败。
     */
    @Test
    void columnMaxCountOutOfRangeShouldFail() {
        ParseProperties properties = PropertiesFixtures.parse();
        properties.setColumnMaxCount(9);

        IllegalStateException error = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(error.getMessage().contains("column-max-count"), () -> "message=" + error.getMessage());
    }

    /**
     * 守护 {@code zeroDisableRulesShouldPass}：允许 0 的计数门槛可关闭规则。
     */
    @Test
    void zeroDisableRulesShouldPass() {
        ParseProperties properties = PropertiesFixtures.parse();
        properties.setShortDocumentMaxUnits(0);
        properties.setShortDocumentMaxFailedUnits(5);
        properties.setScanPageMinChars(0);
        properties.setColumnMinChars(0);
        properties.setColumnMinPageChars(0);

        assertDoesNotThrow(properties::validate);
    }
}
