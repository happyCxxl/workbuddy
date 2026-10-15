package com.workbuddy.common.enums.input;

/**
 * 提交结果状态：每次提交一条，无论成败。
 *
 * @author cxxl
 */
public enum SubmitStatus {

    /** 通过（校验 + 建档三写完成；处理任务不在此登记，由页面手动触发） */
    PASS,

    /** 失败（校验拦截，不建结果不建任务；fail_reason 记原因） */
    FAIL
}
