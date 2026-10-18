package com.workbuddy.worker.input;

import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.worker.input.check.FileCheck;

/**
 * 文件校验入口（文档输入环节）。
 * 实现形态为校验步骤链（{@link FileCheck} 有序步骤，薄编排器 FileValidationPipeline 依次执行）；
 * Tika 3.1.0 真实格式识别 + 白名单 + 大小双源比对 + 损坏/加密探测 + sha256 流式计算，单次下载流内完成。
 */
public interface FileValidatorPort {

    /**
     * 校验文件：单次取流内依次完成 sha256、Tika 魔数识别、大小比对、加密探测。
     *
     * @param fileId 文件 ID
     * @return 校验结果（pass 带 format/size/sha256；fail 带 failReason）
     */
    FileValidationResult validate(String fileId);
}
