package com.workbuddy.common.dto.request.model;

import com.workbuddy.common.dto.request.page.PageQueryDto;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型目录清单的分页查询条件（管理接口收参）：能力 / 状态 / 关键字三项都可留空。
 *
 * <p>能力与状态的取值校验在本类注解（Controller `@Valid` 触发）；关键字在模型名列上做模糊匹配。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ModelQueryDto extends PageQueryDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 能力（可空，字典名：CHAT / EMBEDDING / OCR，大小写不敏感） */
    @Pattern(regexp = "(?i)CHAT|EMBEDDING|OCR", message = "能力取值只能是 CHAT / EMBEDDING / OCR")
    private String capability;

    /** 状态（可空，字典名：ENABLED / DISABLED，大小写不敏感） */
    @Pattern(regexp = "(?i)ENABLED|DISABLED", message = "状态取值只能是 ENABLED 或 DISABLED")
    private String status;

    /** 关键字（可空，模型名模糊匹配） */
    private String keyword;
}
