package com.workbuddy.common.domain.structure;

import lombok.Data;

/**
 * 冲突记录：无法裁决的两路元素（双路并存契约：两路都保留，PRIMARY/BACKUP 标记）。
 */
@Data
public class ConflictRecord {

    /** 主路元素 ID（PRIMARY） */
    private String primaryElementId;

    /** 被裁决方元素 ID（BACKUP） */
    private String backupElementId;

    /** 说明 */
    private String message;
}
