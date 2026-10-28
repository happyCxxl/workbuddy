package com.workbuddy.common.domain.preprocess;

import com.workbuddy.common.domain.task.StageOutcome;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 预处理输出：派生视图 + 状态壳（继承 StageOutcome；warnings 记规则异常隔离）。
 * 状态建议由管线给出，biz 落库回写（与 ParseOutcome/AssembleOutcome 同构）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PreprocessOutcome extends StageOutcome {

    /** 五层派生视图 */
    private PreprocessView view;
}
