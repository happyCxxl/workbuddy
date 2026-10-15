package com.workbuddy.common.dto.response.knowledge;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库-策略绑定视图：未绑定时仅返回 strategyType。
 *
 * <p>单体查询返回一个库的绑定；批量查询（见 {@code GET /knowledge-base/strategy-bindings}）
 * 一次返回该策略类型下所有已绑定的库，每行靠 {@link #knowledgeBaseId} 区分。
 *
 * @author cxxl
 */
@Data
public class StrategyBindingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 知识库 ID（雪花 ID）。
     *
     * <p>单体查询（按库查）时该字段为 null —— 库 ID 由请求路径给出，返回里无需重复；
     * 批量查询时**必有值**，是区分各行的唯一标识。
     */
    private Long knowledgeBaseId;

    /** 策略类型：PREPROCESS / CHUNK */
    private String strategyType;

    /** 绑定的策略版本行 ID（雪花 ID） */
    private Long strategyVersionId;

    /** 绑定策略名 */
    private String strategyName;

    /** 绑定策略版本号 */
    private String strategyVersion;
}
