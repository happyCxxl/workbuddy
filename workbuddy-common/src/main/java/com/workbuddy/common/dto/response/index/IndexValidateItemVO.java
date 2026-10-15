package com.workbuddy.common.dto.response.index;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 索引验证子项：CONSISTENCY（一致性自检）/ VECTOR_SMOKE（向量冒烟）/ FULLTEXT_SMOKE（全文冒烟）。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexValidateItemVO {

    /** 子项名（CONSISTENCY/VECTOR_SMOKE/FULLTEXT_SMOKE） */
    private String name;

    /** 是否通过 */
    private boolean passed;

    /** 明细（如「产物 N 片 vs Milvus M 片」「命中 N 条」） */
    private String detail;
}
