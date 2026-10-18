package com.workbuddy.common.domain.task;

import com.workbuddy.common.enums.task.PipelineTaskStatus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 环节输出公共基类（各环节 Outcome 继承）：状态壳——建议状态/错误码/错误信息/子步骤记录/告警。
 * 各环节只保留自己的业务载荷（parseResult/document/view/chunkSet/embeddingSet），
 * 跨环节的状态判定与子步骤记录字段统一在此声明，不重复。
 */
@Data
public abstract class StageOutcome {

    /** 建议任务状态 */
    private PipelineTaskStatus suggestedStatus;

    /** 失败错误码（PipelineTaskErrorCode 枚举名；成功为空） */
    private String errorCode;

    /** 失败错误信息（失败时非空） */
    private String errorMsg;

    /** 子步骤记录（环节各自维护步骤数与统计字段） */
    private List<StepLogInfo> stepLogs = new ArrayList<>();

    /** 环节内异常隔离告警记录（规则/切片器/批次失败等；内容口径各环节自定） */
    private List<String> warnings = new ArrayList<>();

    /** 失败快捷置位：建议状态 FAILED + 错误码 + 错误信息。 */
    public void fail(String errorCode, String errorMsg) {
        this.suggestedStatus = PipelineTaskStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
    }
}
