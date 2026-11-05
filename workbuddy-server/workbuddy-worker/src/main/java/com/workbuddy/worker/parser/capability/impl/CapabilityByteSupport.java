package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.utils.NullUtil;

import java.util.function.Function;

/**
 * 能力图片字节解析辅助（包内使用）：从 ProviderContext 的资产字节入口按 imageRef 取图。
 */
final class CapabilityByteSupport {

    private CapabilityByteSupport() {
    }

    /**
     * 取图片字节：上下文或解析入口缺失、结果为空时返回 null（调用方按降级处理）。
     *
     * @param ctx      调用上下文
     * @param imageRef 图片引用
     * @return 图片字节；不可用时为 null
     */
    static byte[] resolve(ProviderContext ctx, String imageRef) {
        Function<String, byte[]> resolver = NullUtil.isNull(ctx) ? null : ctx.getAssetBytes();
        byte[] image = NullUtil.isNull(resolver) ? null : resolver.apply(imageRef);
        return NullUtil.isNull(image) || image.length == 0 ? null : image;
    }
}
