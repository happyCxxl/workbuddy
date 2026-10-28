package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 阶段产物数据访问服务（wb_knowledge_pipeline_product）。
 */
public interface WbPipelineProductDbService extends InfraDbService<WbPipelineProduct> {

    /**
     * 按文件结果 + 环节查最新产物（id 倒序取一条）。
     *
     * @param fileResultId 文件结果 ID
     * @param stage        环节（PipelineStage 枚举名）
     * @return 产物实体；不存在返回 null
     */
    WbPipelineProduct getByFileResultIdAndStage(Long fileResultId, String stage);

    /**
     * 按任务查产物（一次执行至多一份产物：重复执行按它复用，不新增行）。
     *
     * @param taskId 任务 ID
     * @return 产物实体；不存在返回 null
     */
    WbPipelineProduct getByTaskId(Long taskId);

    /**
     * 查单文件全部产物（id 升序）。
     *
     * @param fileResultId 文件结果 ID
     * @return 产物列表
     */
    List<WbPipelineProduct> listByFileResultId(Long fileResultId);
}
