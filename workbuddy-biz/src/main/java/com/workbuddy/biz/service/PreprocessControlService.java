package com.workbuddy.biz.service;

import com.workbuddy.common.dto.response.preprocess.PreprocessDetailVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessTriggerVO;

/**
 * 预处理控制面：触发预处理/重跑（手动逐环节）+ 预处理详情。
 *
 * @author cxxl
 */
public interface PreprocessControlService {

    /**
     * 触发预处理（strategyVersionId 可选：显式指定策略版本行 ID（校验存在/类型/启用，任一不满足 40433）；
     * 不传用知识库绑定；两档都不命中一律 40438）。
     *
     * @param fileResultId       文件结果 ID
     * @param strategyVersionId  策略版本行 ID（可空；不传用知识库绑定）
     * @param upstreamProductId 上游产物 ID（必填，指定 STRUCTURE 产物，未指定或传 0 报 40436）
     * @return 任务 ID + 生效策略版本
     */
    PreprocessTriggerVO preprocess(Long fileResultId, Long strategyVersionId, Long upstreamProductId);

    /**
     * 预处理详情：任务状态 + 子步骤 + 策略信息 + 预处理统计/视图元素/产物引用；
     * taskId 可选（缺省取最新任务，传了则查该次运行）。
     */
    PreprocessDetailVO preprocessDetail(Long fileResultId, Long taskId);
}
