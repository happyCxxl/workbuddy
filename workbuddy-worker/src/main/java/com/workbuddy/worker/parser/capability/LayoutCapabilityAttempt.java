package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.capability.LayoutRegion;
import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.utils.NullUtil;

import java.util.List;

/**
 * 复杂页的版面能力尝试：按页面图像调一次版面分析，成功给出「区域清单 + 阅读顺序 + 能力补强事实」，
 * 未接入 / 页面为空 / 调用失败 / 结果不可用给出「无顺序 + 版面降级事实」，由取用方决定是否回落几何口径。
 */
public final class LayoutCapabilityAttempt {

    private LayoutCapabilityAttempt() {
    }

    /**
     * 尝试结果。
     *
     * @param regions 模型给出的区域清单（null 表示未产出）
     * @param order   模型给出的阅读顺序（null 表示未产出）
     * @param fact    留痕事实（补强或降级）
     * @param usage   本次实际用到的能力引用（未产出时为 null）
     */
    public record Outcome(List<LayoutRegion> regions, List<Integer> order, ParseFact fact,
                          CapabilityUsage usage) {

        /** 未产出顺序的结果（无能力引用） */
        public Outcome(List<LayoutRegion> regions, List<Integer> order, ParseFact fact) {
            this(regions, order, fact, null);
        }

        /** 是否拿到可用顺序。 */
        public boolean enhanced() {
            return NullUtil.isNotNull(order) && !order.isEmpty() && NullUtil.isNotNull(regions)
                    && !regions.isEmpty();
        }
    }

    /**
     * 对一页做一次版面能力尝试。
     *
     * @param registry 能力注册表（null 表示未接入）
     * @param page     页面图像（null 表示页面为空）
     * @param pageNo   页码
     * @return 尝试结果（永不为 null）
     */
    public static Outcome attempt(CapabilityRegistry registry, PageImage page, int pageNo) {
        String traceRegion = "page " + pageNo;
        LayoutProvider provider = NullUtil.isNull(registry) ? null : registry.layout();
        if (NullUtil.isNull(provider)) {
            return new Outcome(null, null, CapabilityFacts.layoutDegraded(traceRegion, "未接入能力"));
        }
        if (NullUtil.isNull(page)) {
            return new Outcome(null, null, CapabilityFacts.layoutDegraded(traceRegion, "页面为空"));
        }
        LayoutResult result = provider.analyze(page, new ProviderContext());
        if (NullUtil.isNull(result) || NullUtil.isNull(result.getOrder()) || result.getOrder().isEmpty()
                || NullUtil.isNull(result.getRegions()) || result.getRegions().isEmpty()) {
            return new Outcome(null, null, CapabilityFacts.layoutDegraded(traceRegion, "调用失败或结果不完整"));
        }
        return new Outcome(result.getRegions(), result.getOrder(),
                CapabilityFacts.layoutEnhanced(traceRegion, result),
                new CapabilityUsage(CapabilityUsage.KIND_LAYOUT, result.getModel(), result.getVersion()));
    }
}
