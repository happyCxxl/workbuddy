package com.workbuddy.worker.parser.pdf.page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 页面流水线：按固定顺序执行页面步骤，启动时校验重名与依赖缺失。
 */
public final class PagePipeline {

    private final List<PageStep> steps;

    /**
     * 构造页面流水线。
     *
     * @param steps 有序页面步骤
     */
    public PagePipeline(List<PageStep> steps) {
        this.steps = List.copyOf(steps);
        validate(this.steps);
    }

    /**
     * 顺序执行页面步骤。
     *
     * @param context 页面工作区
     * @return 执行后的页面工作区
     */
    public PageContext run(PageContext context) {
        for (PageStep step : steps) {
            context.getParseContext().getCancellationToken().throwIfCancelled();
            step.apply(context);
        }
        return context;
    }

    private static void validate(List<PageStep> steps) {
        Map<String, PageStep> byName = new LinkedHashMap<>();
        for (PageStep step : steps) {
            if (byName.put(step.name(), step) != null) {
                throw new IllegalStateException("重复页面步骤: " + step.name());
            }
        }
        for (PageStep step : steps) {
            for (String required : step.requires()) {
                if (!byName.containsKey(required)) {
                    throw new IllegalStateException(
                            "页面步骤 " + step.name() + " 缺少依赖: " + required);
                }
            }
        }
    }
}
