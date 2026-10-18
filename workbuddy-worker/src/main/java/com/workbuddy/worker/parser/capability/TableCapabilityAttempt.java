package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.domain.parse.capability.TableResult;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.assemble.TableStructureSupport;

/**
 * 规则失败表的能力尝试：按区域调一次表格结构识别，成功给出「表格元素 + 能力补强事实」，
 * 未接入 / 区域为空 / 调用失败 / 结果不可用给出「无表格元素 + 能力降级事实」，由取用方决定是否降级。
 */
public final class TableCapabilityAttempt {

    private TableCapabilityAttempt() {
    }

    /**
     * 尝试结果。
     *
     * @param table 能力产出的表格元素（null 表示未产出，按内置规则降级）
     * @param fact  留痕事实（补强或降级）
     * @param usage 本次实际用到的能力引用（未产出时为 null）
     */
    public record Outcome(ParseElement table, ParseFact fact, CapabilityUsage usage) {

        /** 未产出结构的结果（无能力引用） */
        public Outcome(ParseElement table, ParseFact fact) {
            this(table, fact, null);
        }

        /** 是否拿到可用结构。 */
        public boolean enhanced() {
            return NullUtil.isNotNull(table);
        }
    }

    /**
     * 对一块表格区域做一次能力尝试。
     *
     * @param registry 能力注册表（null 表示未接入）
     * @param region   表格区域（null 表示区域为空）
     * @param pageNo   页码
     * @return 尝试结果（永不为 null）
     */
    public static Outcome attempt(CapabilityRegistry registry, TableRegion region, int pageNo) {
        String traceRegion = "page " + pageNo;
        TableStructureProvider provider = NullUtil.isNull(registry) ? null : registry.table();
        if (NullUtil.isNull(provider)) {
            return new Outcome(null, CapabilityFacts.tableDegraded(traceRegion, "未接入能力"));
        }
        if (NullUtil.isNull(region)) {
            return new Outcome(null, CapabilityFacts.tableDegraded(traceRegion, "区域为空"));
        }
        TableResult result = provider.recognize(region, new ProviderContext());
        ParseElement table = TableStructureSupport.toTableElement(result, pageNo);
        if (NullUtil.isNull(table)) {
            return new Outcome(null, CapabilityFacts.tableDegraded(traceRegion, "调用失败或结果不完整"));
        }
        return new Outcome(table, CapabilityFacts.tableEnhanced(traceRegion, result),
                CapabilityUsage.table(result.getModel(), result.getVersion()));
    }
}
