package com.workbuddy.common.dto.request.retrieval;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 勾选对比回放请求：按运行记录 ID 回放执行时刻快照并排（不重跑）。
 *
 * @author cxxl
 */
@Data
public class RetrievalRunCompareDto {

    /** 运行记录 ID 列表（按此顺序并排） */
    @NotEmpty(message = "runIds 不能为空")
    private List<Long> runIds;
}
