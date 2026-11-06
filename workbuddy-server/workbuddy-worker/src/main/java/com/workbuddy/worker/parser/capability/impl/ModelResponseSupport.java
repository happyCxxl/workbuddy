package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.modelgateway.OcrResponse;

import java.util.List;

/**
 * 模型响应公共解析：bbox 像素→pt 与 meta.model_key 提取，供各结构化 provider 复用。
 */
final class ModelResponseSupport {

    private ModelResponseSupport() {
    }

    /**
     * 将模型响应的四元 bbox 从像素换算为 pt。
     *
     * @param box          bbox 原始数组
     * @param pixelsPerPt  像素/磅比例
     * @param errorMessage 形态错误时的错误文案
     * @return 左上角 + 宽高的 bbox
     */
    static BBox bbox(List<?> box, double pixelsPerPt, String errorMessage) {
        ThrowUtil.throwIf(NullUtil.isNull(box) || box.size() < 4
                        || !(box.get(0) instanceof Number) || !(box.get(1) instanceof Number)
                        || !(box.get(2) instanceof Number) || !(box.get(3) instanceof Number),
                ErrorCode.MODEL_SERVER_ERROR, errorMessage);
        double x0 = ((Number) box.get(0)).doubleValue() / pixelsPerPt;
        double y0 = ((Number) box.get(1)).doubleValue() / pixelsPerPt;
        double x1 = ((Number) box.get(2)).doubleValue() / pixelsPerPt;
        double y1 = ((Number) box.get(3)).doubleValue() / pixelsPerPt;
        return new BBox(x0, y0, x1 - x0, y1 - y0);
    }

    /**
     * 判断响应中的 bbox 是否为四元数值数组。
     *
     * @param box bbox 原始数组
     * @return 是否可换算
     */
    static boolean hasBbox(List<?> box) {
        return !NullUtil.isNull(box) && box.size() >= 4
                && box.get(0) instanceof Number && box.get(1) instanceof Number
                && box.get(2) instanceof Number && box.get(3) instanceof Number;
    }

    /**
     * 提取网关响应的模型键。
     *
     * @param response 网关响应
     * @return 模型键；缺失时为 null
     */
    static String modelKey(OcrResponse response) {
        Object rawModel = NullUtil.isNull(response.meta()) ? null : response.meta().get("model_key");
        return rawModel instanceof String model ? model : null;
    }
}
