package com.workbuddy.common.domain.model.calllog;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 模型调用明细视图：明细的读取形态，在写入字段之上补行标识与写入时刻。
 *
 * <p>{@code createTime} 是明细写入时刻（紧随调用结束）；一次逻辑调用的耗时另见 {@code latencyMs}。
 * 用量三项为 null 表示未知（配 {@code usageUnknown = 1}），为 0 表示确实为零，两者语义不同。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ModelCallLogView extends ModelCallLogEntry {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 明细写入时刻 */
    private LocalDateTime createTime;
}
