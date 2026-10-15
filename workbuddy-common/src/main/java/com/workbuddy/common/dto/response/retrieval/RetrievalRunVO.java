package com.workbuddy.common.dto.response.retrieval;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索运行记录：四元组 + 耗时 + 时间戳（结果快照按需经对比回放接口展开）。
 *
 * <p>三个雪花 ID 字段都做了字符串序列化：**前端要拿 id 回传调勾选对比接口**，
 * 裸数字下发会被 JSON.parse 丢精度，失真后报"运行记录不存在"。
 *
 * @author cxxl
 */
@Data
public class RetrievalRunVO {

    /** 运行记录 ID（雪花 ID，须字符串化） */
    private Long id;

    /** 索引版本行 ID（雪花 ID，须字符串化） */
    private Long versionId;

    /** 版本号 */
    private String versionNo;

    /** 规则行 ID（雪花 ID，须字符串化） */
    private Long ruleId;

    /** 规则 name-version */
    private String ruleNameVersion;

    /** 查询文本 */
    private String query;

    /** 耗时（毫秒） */
    private Integer elapsedMs;

    /** 执行时间 */
    private LocalDateTime createTime;
}
