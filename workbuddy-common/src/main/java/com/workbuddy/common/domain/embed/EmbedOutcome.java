package com.workbuddy.common.domain.embed;

import com.workbuddy.common.domain.task.StageOutcome;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 向量化输出：EmbeddingSet + 状态壳（继承 StageOutcome；warnings 记质量告警/批次失败隔离）。
 * 状态建议由管线给出，biz 落库回写（与 ChunkOutcome 同构）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EmbedOutcome extends StageOutcome {

    /** 向量产物集合 */
    private EmbeddingSet embeddingSet;
}
