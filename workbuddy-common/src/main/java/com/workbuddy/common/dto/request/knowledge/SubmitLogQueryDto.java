package com.workbuddy.common.dto.request.knowledge;

import com.workbuddy.common.dto.request.page.PageQueryDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 提交记录清单的分页查询条件：状态 / 文件名两项都可留空。
 *
 * <p>页码与页长口径见 {@link PageQueryDto}。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SubmitLogQueryDto extends PageQueryDto {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 提交状态（可空，码值见提交状态字典） */
    private String status;

    /** 文件名模糊关键字（可空） */
    private String fileName;
}
