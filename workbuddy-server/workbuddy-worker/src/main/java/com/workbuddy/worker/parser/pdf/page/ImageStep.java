package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.assemble.PdfImageEmitter;

import java.util.Set;

/**
 * 页内图片：页图与图片裁剪资产、IMAGE 元素和 OCR_IMAGE 事实。
 */
public final class ImageStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "image";
    }

    /**
     * 依赖页脚追加。
     *
     * @return 依赖步骤名
     */
    @Override
    public Set<String> requires() {
        return Set.of("block-render");
    }

    /**
     * 产出图片元素与事实。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        PdfImageEmitter.emit(context.getPage(), context.getElements(), context,
                context.getFileId(), context.getProperties(), context.getParseContext(), context.getRenderer());
    }
}
