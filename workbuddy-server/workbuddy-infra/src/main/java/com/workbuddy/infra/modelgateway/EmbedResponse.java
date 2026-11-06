package com.workbuddy.infra.modelgateway;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量化网关响应（data 段直译）：vectors 与输入同序，meta 为网关元信息。
 * 组装业务实体（EmbeddingResult）归调用方，本类只承载网关返回形态。
 *
 * @param vectors 向量行，与输入同序
 * @param meta 网关元信息
 */
public record EmbedResponse(List<List<Float>> vectors, Map<String, Object> meta) {

    /**
     * 从网关 data 段解析：vectors 缺失或形态错误按 MODEL_SERVER_ERROR 抛出。
     *
     * @param data 网关 data 段
     * @return 直译记录
     */
    public static EmbedResponse from(Map<String, Object> data) {
        Object rawVectors = data.get("vectors");
        List<?> rows = rawVectors instanceof List<?> list ? list : null;
        ThrowUtil.throwIf(NullUtil.isNull(rows), ErrorCode.MODEL_SERVER_ERROR, "向量化响应缺少 vectors");
        List<List<Float>> vectors = new ArrayList<>(rows.size());
        for (Object rawRow : rows) {
            List<?> row = rawRow instanceof List<?> list ? list : null;
            ThrowUtil.throwIf(NullUtil.isNull(row), ErrorCode.MODEL_SERVER_ERROR, "向量化响应格式错误");
            List<Float> vector = new ArrayList<>(row.size());
            for (Object value : row) {
                ThrowUtil.throwIf(!(value instanceof Number), ErrorCode.MODEL_SERVER_ERROR, "向量化响应格式错误");
                vector.add(((Number) value).floatValue());
            }
            vectors.add(vector);
        }
        return new EmbedResponse(vectors, GatewayDataSupport.mapOf(data, "meta"));
    }
}
