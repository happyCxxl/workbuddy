package com.workbuddy.common.dto.request.home;

import com.workbuddy.common.dto.request.page.PageQueryDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 首页「最近提交」查询条件：页码与页长口径见 {@link PageQueryDTO}。
 *
 * <p>筛选条件（按结果 / 按知识库）后续按需在此追加。
 * 可见范围不在这里：提交人由后端从安全上下文取。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HomeRecentSubmitQueryDTO extends PageQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;
}
