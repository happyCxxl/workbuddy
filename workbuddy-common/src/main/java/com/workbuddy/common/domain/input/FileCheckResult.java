package com.workbuddy.common.domain.input;

import com.workbuddy.common.enums.input.FileValidationFailReason;

/**
 * 校验步骤结果（文件校验步骤链）：pass（继续下一步骤）或 fail（携带失败原因，编排器快速失败）。
 *
 * @param passed     是否通过
 * @param failReason 失败原因（通过时为 null）
 */
public record FileCheckResult(boolean passed, FileValidationFailReason failReason) {

    /** 通过工厂。 */
    public static FileCheckResult pass() {
        return new FileCheckResult(true, null);
    }

    /** 失败工厂：只携带失败原因。 */
    public static FileCheckResult fail(FileValidationFailReason reason) {
        return new FileCheckResult(false, reason);
    }
}
