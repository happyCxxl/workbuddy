package com.workbuddy.common.dto.request.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型启停的请求体：只带目标状态。
 *
 * @author cxxl
 */
@Data
public class ModelStatusDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标状态（必填，取值 ENABLED / DISABLED） */
    private String status;
}
