package com.workbuddy.common.dto.response.structure;

import lombok.Data;

/**
 * 冲突记录 VO：ConflictRecord 的对外视图，树内两路都留，PRIMARY 为主路、BACKUP 为被裁决方。
 */
@Data
public class StructureConflictVO {

    /** 主路元素 ID（PRIMARY） */
    private String primaryElementId;

    /** 被裁决方元素 ID（BACKUP） */
    private String backupElementId;

    /** 说明 */
    private String message;
}
