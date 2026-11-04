package com.workbuddy.biz.service;

import com.workbuddy.common.dto.response.parse.ParseDetailVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;

/**
 * 解析控制面服务：触发解析（手动逐环节，首次解析与失败重跑同一入口）与解析详情。
 */
public interface ParseControlService {

    /**
     * 触发解析（手动逐环节，链路起点）：执行键 = 文件结果（解析没有上游产物与策略版本）。
     * 已有任务（任何状态）一律拒绝 40431，没有才新建 PARSE 任务并入队；完成后停在终态，不自动触发组装。
     */
    StageTriggerVO parse(Long fileResultId);

    /**
     * 解析详情：任务状态 + 子步骤列表 + 产物引用/告警。
     *
     * @param fileResultId 文件结果 ID
     * @param taskId       可选：指定某次运行的任务查看历史详情；为空取最新任务
     */
    ParseDetailVO parseDetail(Long fileResultId, Long taskId);
}
