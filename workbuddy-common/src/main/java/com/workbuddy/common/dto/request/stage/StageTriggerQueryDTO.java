package com.workbuddy.common.dto.request.stage;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 环节触发查询条件（切片 / 预处理 / 向量化三处触发入口共用）。
 *
 * <p>strategyVersionId 可选：不传用知识库绑定的策略版本（两档都不命中报 40438）。
 * upstreamProductId 必填：未指定或传 0 报 40436，不存在按 40432 拒绝。
 * 两字段按同名的独立查询参数绑定。
 *
 * @author cxxl
 */
@Data
public class StageTriggerQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 策略版本行 ID（可选；不传用知识库绑定的策略版本：显式指定 → KB 绑定，两档都不命中报 40438） */
    private Long strategyVersionId;

    /** 上游产物 ID（必填；未指定或传 0 报 40436，取该环节上一环节的指定产物） */
    private Long upstreamProductId;
}
