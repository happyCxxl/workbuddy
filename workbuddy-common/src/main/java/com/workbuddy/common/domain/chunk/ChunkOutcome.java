package com.workbuddy.common.domain.chunk;

import com.workbuddy.common.domain.task.StageOutcome;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 切片输出：ChunkSet + 状态壳（继承 StageOutcome；warnings 记切片器异常隔离）。
 * 状态建议由管线给出，biz 落库回写（与 ParseOutcome/PreprocessOutcome 同构）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChunkOutcome extends StageOutcome {

    /** 切片产物集合 */
    private ChunkSet chunkSet;
}
