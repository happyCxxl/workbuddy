package com.workbuddy.common.domain.structure;

import lombok.Data;

import java.util.List;

/**
 * 续表判定上下文（模型判断兜底输入）。
 *
 * @author cxxl
 */
@Data
public class ContinuationJudgeContext {

    /** 上游表格表头行文本 */
    private List<String> tableAHeaders;

    /** 上游表格列数 */
    private Integer tableAColumns;

    /** 下游表格表头行文本（无表头时为空） */
    private List<String> tableBHeaders;

    /** 下游表格列数 */
    private Integer tableBColumns;

    /** 上游表格所在页 */
    private Integer pageA;

    /** 下游表格所在页 */
    private Integer pageB;
}
