package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.modelgateway.ModelGatewayClient;
import com.workbuddy.infra.modelgateway.OcrResponse;
import com.workbuddy.worker.parser.ParseProperties;

/**
 * 结构化能力网关实现的公共骨架（模板方法）：
 * 取图字节（缺失返回 null，不发调用）→ 拼请求 ID → 网关调用 → 响应映射与 meta 回填。
 *
 * <p>三个实现（表格结构 / 版面分析 / 结构化 OCR）只保留请求 ID 前缀、网关调用与
 * schema 映射三处差异；像素→pt 的 dpi 换算与 model_key 提取收在骨架里。
 *
 * @param <T> 能力结果类型（TableResult / LayoutResult / StructuredOcrResult）
 */
abstract class ModelGatewayStructuredProviderSupport<T> {

    /** 模型网关客户端（子类 invokeGateway 用） */
    protected final ModelGatewayClient modelGatewayClient;
    private final ParseProperties properties;

    /**
     * 构造骨架。
     *
     * @param modelGatewayClient 模型网关客户端
     * @param properties         解析配置（页图 DPI）
     */
    ModelGatewayStructuredProviderSupport(ModelGatewayClient modelGatewayClient, ParseProperties properties) {
        this.modelGatewayClient = modelGatewayClient;
        this.properties = properties;
    }

    /**
     * 请求 ID 前缀。
     *
     * @return 形如 "parse-table" 的前缀
     */
    abstract String requestIdPrefix();

    /**
     * 网关调用：业务键、场景键与调用方法由子类定。
     *
     * @param requestId 请求 ID
     * @param image     图片字节
     * @return 网关响应
     */
    abstract OcrResponse invokeGateway(String requestId, byte[] image);

    /**
     * 网关响应 → 能力结果：schema 映射差异只在此处。
     *
     * @param response    网关响应
     * @param pixelsPerPt 像素/磅比例（页图 DPI ÷ 72）
     * @return 能力结果
     */
    abstract T mapResponse(OcrResponse response, double pixelsPerPt);

    /**
     * 模板方法：取图 → 拼请求 → 调用 → 映射。
     *
     * @param ctx      调用上下文（含资产字节解析入口）
     * @param imageRef 图片引用
     * @param pageNo   页码（进请求 ID）
     * @return 能力结果；图片缺失时为 null
     */
    final T recognize(ProviderContext ctx, String imageRef, int pageNo) {
        byte[] image = CapabilityByteSupport.resolve(ctx, imageRef);
        if (NullUtil.isNull(image)) {
            return null;
        }
        String requestId = requestIdPrefix() + ctx.getTaskId() + "-p" + pageNo;
        return mapResponse(invokeGateway(requestId, image), properties.getPageImageDpi() / 72.0);
    }

    /**
     * 模型键提取：meta.model_key 缺失时为 null。
     *
     * @param response 网关响应
     * @return 模型键
     */
    final String modelKey(OcrResponse response) {
        return ModelResponseSupport.modelKey(response);
    }
}
