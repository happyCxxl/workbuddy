package com.workbuddy.worker.parser.pdf.page.table;

import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.capability.TableCapabilityAttempt;
import com.workbuddy.worker.parser.pdf.assemble.TableRegionSupport;
import com.workbuddy.worker.parser.pdf.asset.PdfPageImageRenderer;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.model.PageContent;

/**
 * 模型表格策略：规则判不出结构时按区域调一次表格结构能力。
 * 成功给「表格元素 + 能力补强事实 + 能力引用」，未接入/调用失败/结果不可用给「能力降级事实」，
 * 由回退链按原失败理由继续降级。
 */
public final class ModelTableBuilder implements TableBuilder {

    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * 对一块失败表区域做一次能力构建。
     *
     * @param input 构建输入
     * @return 构建结果；能力未产出结构时按不可成表失败（事实已留痕）
     */
    @Override
    public Result build(Input input) {
        PageContent page = input.context().getPage();
        TableRegion region = TableRegionSupport.of(input.lines(), page,
                LineTexts.Options.of(input.context().getProperties()));
        ProviderContext providerContext = new ProviderContext();
        providerContext.setTaskId(input.context().getParseContext().getFileResultId());
        providerContext.setAssetBytes(ref -> cropPng(page, region, input.context().getRenderer()));
        TableCapabilityAttempt.Outcome outcome = TableCapabilityAttempt.attempt(
                input.context().getCapabilityRegistry(), region, page.pageNo(), providerContext);
        input.context().getFacts().add(outcome.fact());
        if (outcome.enhanced()) {
            input.context().getCapabilities().add(outcome.usage());
            return Result.built(outcome.table());
        }
        return Result.failed(Failure.UNWORTHY, null);
    }

    /**
     * 区域裁剪图字节：渲染页图后按视口 bbox 裁剪；渲染器缺失返回空数组（真实实现按需取值，桩实现可忽略）。
     */
    private static byte[] cropPng(PageContent page, TableRegion region, PdfPageImageRenderer renderer) {
        if (NullUtil.isNull(renderer) || NullUtil.isNull(region) || NullUtil.isNull(region.getBbox())) {
            return EMPTY_BYTES;
        }
        java.awt.image.BufferedImage source = renderer.pageImage(page.pageNo());
        java.awt.image.BufferedImage crop = NullUtil.isNull(source) ? null
                : renderer.crop(source, region.getBbox(), page.geometry().viewportWidth(),
                        page.geometry().viewportHeight());
        byte[] png = NullUtil.isNull(crop) ? null : renderer.png(crop);
        return NullUtil.isNull(png) ? EMPTY_BYTES : png;
    }
}
