package com.workbuddy.common.dto.request.knowledge;

import com.workbuddy.common.dto.request.page.PageQueryDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 知识库清单的分页查询条件：名称 / 状态 / 排序三项都可留空。
 *
 * <p>页码与页长口径见 {@link PageQueryDto}；排序取值 DEFAULT / UPDATED / NAME，未知取值按默认。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeBaseQueryDto extends PageQueryDto {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 名称模糊关键字（可空） */
    private String name;

    /** 状态（可空，码值见知识库状态字典） */
    private Integer status;

    /** 排序口径（可空：DEFAULT 默认 / UPDATED 最近更新 / NAME 名称） */
    private String sort;
}
