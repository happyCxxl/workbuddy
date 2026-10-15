package com.workbuddy.biz.service;

import com.workbuddy.common.dto.response.structure.StructureDetailVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;

/**
 * 结构组装控制面服务（组装环节）：触发组装（手动逐环节，重跑同入口）+ 组装详情。
 *
 * @author cxxl
 */
public interface StructureControlService {

    /**
     * 触发组装：上游解析产物校验（必填，未指定或传 0 报 40436）→ 执行键查重（已有任务一律拒绝 40431）→
     * 新建 STRUCTURE 任务入队（不重新解析）；upstreamProductId 必填（指定上游解析产物）；
     * 指定产物不存在或不属于该文件结果时拒绝（40432）；QUEUED 补投归启动补偿与低频扫描。
     */
    StageTriggerVO structure(Long fileResultId, Long upstreamProductId);

    /**
     * 组装详情：任务状态 + 子步骤 + 组装统计/冲突/章节/产物引用；
     * taskId 可选（缺省取最新任务，传了则查该次运行）。
     */
    StructureDetailVO structureDetail(Long fileResultId, Long taskId);
}
