package com.workbuddy.common.dto.request.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型目录清单的查询条件（管理接口收参）：能力 / 状态 / 关键字三项都可选。
 *
 * <p>能力与状态按字典名传入，取值解析与非法取值拒绝落在服务层；关键字在模型名与展示名两列上做模糊匹配。
 *
 * @author cxxl
 */
@Data
public class ModelQueryDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 能力（可选，字典名：CHAT / EMBEDDING / OCR / LAYOUT / TABLE_STRUCTURE） */
    private String capability;

    /** 状态（可选，字典名：ENABLED / DISABLED） */
    private String status;

    /** 关键字（可选，匹配模型名或展示名） */
    private String keyword;
}
