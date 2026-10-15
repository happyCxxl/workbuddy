package com.workbuddy.common.domain.structure;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关系：type + from/to 元素 ID + 说明。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRelation {

    /** 关系类型（RelationType 枚举名） */
    private String type;

    /** 源元素 ID（或带页片段后缀，如 t-xxx#p2） */
    private String from;

    /** 目标元素 ID */
    private String to;

    /** 说明（如接续判定依据） */
    private String detail;
}
