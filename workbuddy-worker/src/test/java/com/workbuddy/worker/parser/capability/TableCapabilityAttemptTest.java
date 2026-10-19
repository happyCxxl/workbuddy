package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.TableCell;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.domain.parse.capability.TableResult;
import com.workbuddy.common.enums.parse.ElementType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力尝试单测（测试桩 provider，不走 HTTP）：拿到结构时出表格元素 + 补强事实；
 * 未接入 / 区域为空 / 结果不完整三种情况都只给降级留痕。
 */
class TableCapabilityAttemptTest {

    /** 桩 provider 给出 1×2 表（首格表头）→ 表格元素 + 补强事实（{@code stubProviderShouldYieldTableAndEnhancedFact}）。 */
    @Test
    void stubProviderShouldYieldTableAndEnhancedFact() {
        TableResult result = new TableResult();
        result.setRows(1);
        result.setCols(2);
        result.setModel("stub-table");
        result.setVersion("0.1");
        TableCell header = new TableCell();
        header.setRow(0);
        header.setCol(0);
        header.setColSpan(2);
        header.setText("合计");
        header.setIsHeader(true);
        result.setCells(List.of(header));

        TableCapabilityAttempt.Outcome outcome =
                TableCapabilityAttempt.attempt(registry(result), region(), 4);

        assertTrue(outcome.enhanced());
        ParseElement table = outcome.table();
        assertEquals(ElementType.TABLE.name(), table.getType());
        assertEquals(4, table.getPage());
        assertEquals(0, table.getHeaderRow());
        assertEquals(2, table.getCells().getFirst().getColSpan());
        assertNull(table.getId(), "元素 ID 由页内后处理补");
        assertTrue(outcome.fact().getEvidence().contains("能力补强"), () -> outcome.fact().getEvidence());
        assertTrue(outcome.fact().getEvidence().contains("stub-table 0.1"), () -> outcome.fact().getEvidence());
    }

    /** 未接入能力：无表格元素，留痕为降级（{@code missingRegistryShouldYieldDegradedFactOnly}）。 */
    @Test
    void missingRegistryShouldYieldDegradedFactOnly() {
        TableCapabilityAttempt.Outcome outcome = TableCapabilityAttempt.attempt(null, region(), 1);

        assertFalse(outcome.enhanced());
        assertNull(outcome.table());
        assertEquals("page 1", outcome.fact().getRegion());
        assertTrue(outcome.fact().getEvidence().contains("未接入能力"), () -> outcome.fact().getEvidence());
    }

    /** 区域为空：降级留痕点名原因（{@code emptyRegionShouldYieldDegradedFact}）。 */
    @Test
    void emptyRegionShouldYieldDegradedFact() {
        TableCapabilityAttempt.Outcome outcome = TableCapabilityAttempt.attempt(registry(new TableResult()), null, 2);

        assertFalse(outcome.enhanced());
        assertTrue(outcome.fact().getEvidence().contains("区域为空"), () -> outcome.fact().getEvidence());
    }

    /** 桩 provider 返回空结果：无表格元素，降级原因写「调用失败或结果不完整」（{@code unusableResultShouldYieldDegradedFact}）。 */
    @Test
    void unusableResultShouldYieldDegradedFact() {
        TableCapabilityAttempt.Outcome outcome =
                TableCapabilityAttempt.attempt(registry(null), region(), 3);

        assertFalse(outcome.enhanced());
        assertTrue(outcome.fact().getEvidence().contains("调用失败或结果不完整"),
                () -> outcome.fact().getEvidence());
    }

    private TableRegion region() {
        TableRegion region = new TableRegion();
        region.setPage(1);
        return region;
    }

    /** 只接表格能力的注册表（其余两项返回 null） */
    private CapabilityRegistry registry(TableResult result) {
        return new CapabilityRegistry() {
            @Override
            public StructuredOcrProvider ocr() {
                return null;
            }

            @Override
            public LayoutProvider layout() {
                return null;
            }

            @Override
            public TableStructureProvider table() {
                return (tableRegion, ctx) -> result;
            }
        };
    }
}
