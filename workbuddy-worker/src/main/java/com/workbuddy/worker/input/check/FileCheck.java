package com.workbuddy.worker.input.check;

import com.workbuddy.common.domain.input.FileCheckResult;
import com.workbuddy.common.enums.input.FileCheckStepName;
import com.workbuddy.worker.input.FileCheckContext;

/**
 * 文件校验步骤（责任链）：文件输入环节校验链的一个有序步骤。
 * 薄编排器（FileValidationPipeline）按 {@link #order()} 升序依次执行，首个失败即终止（fail 快速失败）；
 * 步骤间经 {@link FileCheckContext} 共享单次下载流的累积状态（大小/指纹/缓冲/识别结果），
 * 步骤自身无状态。新增校验 = 新增实现类 + 枚举常量（order 间隔 10 预留插入位），既有步骤零改动。
 */
public interface FileCheck {

    /** 执行顺序（升序；间隔 10 预留插入位） */
    int order();

    /** 步骤名（诊断/日志用） */
    FileCheckStepName name();

    /** 执行本步骤校验；不通过返回 fail（携带失败原因），通过返回 pass。 */
    FileCheckResult check(FileCheckContext context);
}
