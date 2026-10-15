package com.workbuddy.common.domain.model;

import lombok.Data;

/**
 * 视觉模态入参：模型名 + 图像 + 提示词 + 输出约束。
 *
 * @author cxxl
 */
@Data
public class VisionRequest {

    /** 厂商侧模型名（wb_model.model_key） */
    private String modelKey;

    /** 图像引用（对象存储 key / URL，可空） */
    private String imageRef;

    /** 图像字节（与 imageRef 二选一） */
    private byte[] imageBytes;

    /** 随 imageBytes 一起提交的媒体类型（如 image/png / image/jpeg，可空）；携带 imageBytes 时必须给出 */
    private String mimeType;

    /** 区域（可空 = 整图） */
    private ImageRegion region;

    /** 提示词 */
    private String prompt;

    /** 输出约束 */
    private OutputSpec output;

    /** 契约保留字段（毫秒，可空）；当前超时以实例级配置为准，LC4j 1.22.0 无 per-request 超时入口 */
    private Integer timeoutMs;
}
