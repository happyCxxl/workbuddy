package com.workbuddy.worker.preprocessing.strategy;

import com.workbuddy.common.enums.preprocess.PreprocessParam;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预处理策略解析器单测：结构化解析补全默认 / 重复旧开关换算 / 空与非法快照抛异常 / custom 解析。
 */
class PreprocessStrategyParserTest {

    private final PreprocessStrategyParser parser = new PreprocessStrategyParser(new PreprocessProperties());

    /**
     * 守护 {@code structuredConfigShouldFillMissingDefaults}：该场景行为改动即失败。
     */
    @Test
    void structuredConfigShouldFillMissingDefaults() {
        PreprocessStrategy strategy = parser.parse("{\"rules\":{\"headerFooter\":{\"action\":\"EXCLUDE\"}}}");

        assertEquals("EXCLUDE", strategy.action(PreprocessRule.HEADER_FOOTER, "MARK"));
        assertEquals("MARK", strategy.action(PreprocessRule.TOC, "MARK"));
        assertEquals("MARK", strategy.action(PreprocessRule.NOISE, "MARK"));
        assertTrue(strategy.enabled(PreprocessRule.FIELD, false));
        assertTrue(strategy.enabled(PreprocessRule.TIDY, false));
        assertTrue(strategy.enabled(PreprocessRule.ENCODING, false));
        // 重复默认档 = 剔除（与历史上开关 ON 的效果一致）
        assertEquals("EXCLUDE", strategy.action(PreprocessRule.REPEAT, "MARK"));
        // 参数补默认（值源 PreprocessProperties）
        assertEquals(3, strategy.intParam(PreprocessRule.TOC, PreprocessParam.TOC_MIN_LINES_PER_PAGE, 0));
        assertTrue(strategy.boolParam(PreprocessRule.TIDY, PreprocessParam.TIDY_URLS, false));
        assertTrue(strategy.boolParam(PreprocessRule.FIELD, PreprocessParam.FIELD_AMOUNT, false));
        // custom 默认
        assertEquals(PreprocessStrategy.ON, strategy.getCustom().getEnabled());
        assertTrue(strategy.customRules().isEmpty());
    }

    /**
     * 守护 {@code emptyObjectSnapshotShouldStillFillDefaults}：该场景行为改动即失败。
     */
    @Test
    void emptyObjectSnapshotShouldStillFillDefaults() {
        // 只有"空串/JSON 非法"算数据问题：合法 JSON 缺键由补全默认参数覆盖
        PreprocessStrategy strategy = parser.parse("{}");

        assertNotNull(strategy);
        assertEquals("MARK", strategy.action(PreprocessRule.HEADER_FOOTER, ""));
    }

    /**
     * 守护 {@code blankSnapshotShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void blankSnapshotShouldThrow() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> parser.parse(null));

        assertTrue(e.getMessage().contains("预处理策略快照为空或已损坏，无法解析"), e.getMessage());
    }

    /**
     * 守护 {@code invalidSnapshotShouldThrowWithTruncatedSnapshot}：该场景行为改动即失败。
     */
    @Test
    void invalidSnapshotShouldThrowWithTruncatedSnapshot() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> parser.parse("not-a-json"));

        assertTrue(e.getMessage().contains("预处理策略快照为空或已损坏，无法解析"), e.getMessage());
        assertTrue(e.getMessage().contains("not-a-json"), e.getMessage());
        assertNotNull(e.getCause());
    }

    /**
     * 守护 {@code customRulesShouldParse}：该场景行为改动即失败。
     */
    @Test
    void customRulesShouldParse() {
        PreprocessStrategy strategy = parser.parse(
                "{\"custom\":{\"enabled\":\"ON\",\"rules\":["
                        + "{\"pattern\":\"版权所有\\\\s*©.*\",\"action\":\"REMOVE\"},"
                        + "{\"pattern\":\"(电话[:：])(\\\\d+)\",\"action\":\"REPLACE\",\"replacement\":\"$1***\"}]}}");

        assertEquals(2, strategy.customRules().size());
        assertEquals("版权所有\\s*©.*", strategy.customRules().get(0).getPattern());
        assertEquals("REMOVE", strategy.customRules().get(0).getAction());
        assertEquals("$1***", strategy.customRules().get(1).getReplacement());
    }

    /**
     * 守护 {@code customOffShouldYieldEmptyRules}：该场景行为改动即失败。
     */
    @Test
    void customOffShouldYieldEmptyRules() {
        PreprocessStrategy strategy = parser.parse(
                "{\"custom\":{\"enabled\":\"OFF\",\"rules\":[{\"pattern\":\"x+\",\"action\":\"REMOVE\"}]}}");

        assertTrue(strategy.customRules().isEmpty());
    }

    /**
     * 守护 {@code legacyRepeatToggleShouldMapToAction}：该场景行为改动即失败。
     */
    @Test
    void legacyRepeatToggleShouldMapToAction() {
        assertEquals("KEEP", parser.parse("{\"rules\":{\"repeat\":{\"enabled\":\"OFF\"}}}")
                .action(PreprocessRule.REPEAT, "MARK"));
        assertEquals("EXCLUDE", parser.parse("{\"rules\":{\"repeat\":{\"enabled\":\"ON\"}}}")
                .action(PreprocessRule.REPEAT, "MARK"));
    }

    /**
     * 守护 {@code explicitRepeatActionShouldWinOverDefault}：该场景行为改动即失败。
     */
    @Test
    void explicitRepeatActionShouldWinOverDefault() {
        assertEquals("MARK", parser.parse("{\"rules\":{\"repeat\":{\"action\":\"MARK\"}}}")
                .action(PreprocessRule.REPEAT, "EXCLUDE"));
    }

    /**
     * 守护 {@code parseConfigShouldLeaveNameVersionNull}：该场景行为改动即失败。
     */
    @Test
    void parseConfigShouldLeaveNameVersionNull() {
        PreprocessStrategy strategy = parser.parseConfig("{\"rules\":{}}");

        assertNull(strategy.getName());
        assertNull(strategy.getVersion());
        assertEquals("MARK", strategy.action(PreprocessRule.HEADER_FOOTER, ""));
    }

    /**
     * 守护 {@code parseConfigShouldThrowOnCorruptSnapshot}：该场景行为改动即失败。
     */
    @Test
    void parseConfigShouldThrowOnCorruptSnapshot() {
        // parseConfig 与 parse 同一口径：坏快照抛异常，不再返回默认策略
        assertThrows(IllegalStateException.class, () -> parser.parseConfig("{oops"));
        assertThrows(IllegalStateException.class, () -> parser.parseConfig(null));
    }
}
