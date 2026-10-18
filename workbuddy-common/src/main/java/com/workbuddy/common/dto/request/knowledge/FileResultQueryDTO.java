package com.workbuddy.common.dto.request.knowledge;

import com.workbuddy.common.dto.request.page.PageQueryDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 文件结果清单的分页查询条件：环节可留空。
 *
 * <p>页码与页长口径见 {@link PageQueryDTO}；环节合法值 PARSE / STRUCTURE / PREPROCESS / CHUNK / EMBED，
 * 非法取值在服务层按 40001 拒绝。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FileResultQueryDTO extends PageQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 环节（可空：PARSE / STRUCTURE / PREPROCESS / CHUNK / EMBED） */
    private String stage;
}
