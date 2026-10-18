package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.capability.TableResult;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.SignalType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力留痕单测：补强与降级都挂 TABLE 事实，证据文案区分两类，模型名缺省有落点。
 */
class CapabilityFactsTest {

    /** 补强事实：证据带模型名、版本与行列数 */
    @Test
    void enhancedFactShouldCarryModelAndGrid() {
        TableResult result = new TableResult();
        result.setRows(3);
        result.setCols(4);
        result.setModel("tableformer");
        result.setVersion("1.0");

        ParseFact fact = CapabilityFacts.tableEnhanced("page 2", result);

        assertEquals(SignalType.TABLE, fact.getType());
        assertEquals("page 2", fact.getRegion());
        assertTrue(fact.getEvidence().contains("tableformer 1.0"), () -> "evidence=" + fact.getEvidence());
        assertTrue(fact.getEvidence().contains("3 行 × 4 列"), () -> "evidence=" + fact.getEvidence());
    }

    /** 模型名/版本缺省：证据仍成句 */
    @Test
    void enhancedFactShouldTolerateMissingModel() {
        ParseFact fact = CapabilityFacts.tableEnhanced("page 1", new TableResult());

        assertTrue(fact.getEvidence().contains("model -"), () -> "evidence=" + fact.getEvidence());
        assertTrue(fact.getEvidence().contains("0 行 × 0 列"), () -> "evidence=" + fact.getEvidence());
    }

    /** 降级事实：证据带失败原因；原因缺省时给「未配置」 */
    @Test
    void degradedFactShouldCarryReason() {
        ParseFact timeout = CapabilityFacts.tableDegraded("page 3", "读超时");
        ParseFact blank = CapabilityFacts.tableDegraded("page 4", null);

        assertEquals(SignalType.TABLE, timeout.getType());
        assertTrue(timeout.getEvidence().contains("读超时"), () -> "evidence=" + timeout.getEvidence());
        assertTrue(timeout.getEvidence().contains("已按内置规则降级"), () -> "evidence=" + timeout.getEvidence());
        assertTrue(blank.getEvidence().contains("未配置"), () -> "evidence=" + blank.getEvidence());
    }
}
