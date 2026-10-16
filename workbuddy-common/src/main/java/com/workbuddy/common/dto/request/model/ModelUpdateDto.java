package com.workbuddy.common.dto.request.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 编辑模型的请求体：字段与校验口径同 {@link ModelSaveDto}（编辑可改厂商与模型名，唯一键判定排除自身）。
 *
 * <p>状态不在此：新增固定启用、编辑保持原值，启停只走专用接口。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ModelUpdateDto extends ModelSaveDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
