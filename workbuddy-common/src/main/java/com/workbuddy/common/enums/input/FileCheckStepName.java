package com.workbuddy.common.enums.input;

/**
 * 文件校验步骤名（FileCheck 步骤的 stepName；同 ParseStepName 先例：步骤名枚举化收口）。
 */
public enum FileCheckStepName {

    /** 大小统计与上限 */
    SIZE_LIMIT("大小统计与上限"),

    /** 空文件判定 */
    EMPTY_FILE("空文件判定"),

    /** 大小双源比对 */
    METADATA_MATCH("大小双源比对"),

    /** 魔数识别与白名单 */
    MAGIC_TYPE("魔数识别与白名单"),

    /** 损坏与加密探测 */
    CORRUPTION_PROBE("损坏与加密探测");

    /** 步骤名（人类可读，落日志/诊断） */
    private final String value;

    FileCheckStepName(String value) {
        this.value = value;
    }

    /**
     * 步骤名（人类可读）。
     *
     * @return 步骤名
     */
    public String value() {
        return value;
    }
}
