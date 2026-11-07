package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutCapabilityAttempt;
import com.workbuddy.worker.parser.pdf.assemble.LayoutComplexity;
import com.workbuddy.worker.parser.pdf.assemble.LayoutOrderSupport;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.List;
import java.util.Set;

/**
 * 阅读顺序：复杂页且版面能力可用时按模型区域重排正文行，否则保持几何顺序。
 */
public final class ReadingOrderStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "reading-order";
    }

    /**
     * 依赖正文行。
     *
     * @return 依赖步骤名
     */
    @Override
    public Set<String> requires() {
        return Set.of();
    }

    /**
     * 计算阅读顺序。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        List<PageLine> bodyLines = context.getBodyLines();
        if (!shouldAskLayout(context, bodyLines)) {
            context.setOrderedLines(bodyLines);
            return;
        }
        PageContent page = context.getPage();
        PageImage image = new PageImage();
        image.setPage(page.pageNo());
        image.setWidth(page.pageWidth());
        image.setHeight(page.pageHeight());
        image.setImageRef("page-" + page.pageNo() + "-layout");
        ProviderContext providerContext = new ProviderContext();
        providerContext.setTaskId(context.getParseContext().getFileResultId());
        providerContext.setAssetBytes(ref -> NullUtil.isNull(context.getRenderer())
                ? null : context.getRenderer().png(context.getRenderer().pageImage(page.pageNo())));
        LayoutCapabilityAttempt.Outcome outcome = LayoutCapabilityAttempt.attempt(
                context.getCapabilityRegistry(), image, page.pageNo(), providerContext);
        context.getFacts().add(outcome.fact());
        if (!outcome.enhanced()) {
            context.setOrderedLines(bodyLines);
            return;
        }
        context.getCapabilities().add(outcome.usage());
        context.setOrderedLines(LayoutOrderSupport.reorder(bodyLines, outcome.regions(), outcome.order()));
    }

    private static boolean shouldAskLayout(PageContext context, List<PageLine> bodyLines) {
        CapabilityRegistry registry = context.getCapabilityRegistry();
        ParseProperties properties = context.getProperties();
        return NullUtil.isNotNull(registry) && LayoutComplexity.isComplex(bodyLines, properties);
    }
}
