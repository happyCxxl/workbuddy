package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.infra.persistence.InfraDbService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处理链任务数据访问服务（wb_knowledge_pipeline_task）。
 *
 * @author cxxl
 */
public interface WbPipelineTaskDbService extends InfraDbService<WbPipelineTask> {

    /**
     * 按文件结果 + 环节查最新任务（id 倒序取一条）。
     *
     * @param fileResultId 文件结果 ID
     * @param stage        环节（PipelineStage 枚举名）
     * @return 任务实体；不存在返回 null
     */
    WbPipelineTask getByFileResultIdAndStage(Long fileResultId, String stage);

    /**
     * 按执行键查任务：同一份输入（上游产物）与同一策略版本只允许一条任务（唯一键 uk_exec 的应用侧查询）。
     *
     * @param fileResultId      文件结果 ID
     * @param stage             环节（PipelineStage 枚举名）
     * @param upstreamProductId 上游产物 ID（0 表示没有上游）
     * @param strategyVersionId 策略版本 ID（0 表示该环节无策略）
     * @return 任务实体；不存在返回 null
     */
    WbPipelineTask getByExecKey(Long fileResultId, String stage, Long upstreamProductId, Long strategyVersionId);

    /**
     * 按文件结果查全部环节任务（id 升序；执行链血缘数据源）。
     */
    List<WbPipelineTask> listByFileResultId(Long fileResultId);

    /**
     * 批量查指定环节任务（列表行环节状态用）：fileResultIds 内 + stage，id 倒序
     * （同一 fileResultId 的首条即最新任务）。
     *
     * @param fileResultIds 文件结果 ID 列表（为空返回空列表，不查库）
     * @param stage         环节（PipelineStage 枚举名）
     * @return 任务列表
     */
    List<WbPipelineTask> listByFileResultIdsAndStage(List<Long> fileResultIds, String stage);

    /**
     * 查指定状态的任务（id 升序；只取任务 ID、文件结果与状态三列）。
     *
     * @param statuses 状态集合（为空返回空列表，不查库）
     * @return 任务列表
     */
    List<WbPipelineTask> listByStatuses(List<PipelineTaskStatus> statuses);

    /**
     * 查过期未执行的 QUEUED 任务（启动补偿/低频兜底用）。
     *
     * @param threshold 创建时间阈值（早于该时间视为过期）
     * @return QUEUED 且创建时间早于阈值的任务列表
     */
    List<WbPipelineTask> listStaleQueued(LocalDateTime threshold);

    /**
     * 查孤儿执行中任务（孤儿恢复用）。
     *
     * @param threshold 开始时间阈值（早于该时间视为孤儿）
     * @return RUNNING 且开始时间早于阈值的任务列表
     */
    List<WbPipelineTask> listStaleRunning(LocalDateTime threshold);

    /**
     * 终态回写：RUNNING → 指定终态 + 结束时间 + 错误码/信息（条件更新，后到者得 0）。
     * 条件更新是编排层互斥的依据：看门狗超时回写与执行器成功/失败回写只可能有一个生效。
     * 目标态必须是 {@link PipelineTaskStatus#RUNNING} 的合法目标态，非法目标态在写库前抛
     * IllegalArgumentException。
     *
     * @param id        任务 ID
     * @param status    目标终态
     * @param errorCode 错误码（失败时传入；成功为 null）
     * @param errorMsg  错误信息（失败时传入；成功为 null）
     * @return 受影响行数
     */
    int finish(Long id, PipelineTaskStatus status, String errorCode, String errorMsg);

    /**
     * 未入队即失败回写：QUEUED → FAILED + 结束时间 + 错误码/信息（条件更新，已被领取的任务不受影响）。
     * 与 {@link #claim} 的条件互斥：入队失败回写与消费者领取只可能有一个生效，另一方影响 0 行。
     *
     * @param id        任务 ID
     * @param errorCode 错误码
     * @param errorMsg  错误信息
     * @return 受影响行数
     */
    int failQueued(Long id, String errorCode, String errorMsg);

    /**
     * 扫库领批（多环节）：QUEUED + 环节在给定集合内，id 升序，限量。
     *
     * @param stages 环节集合（为空返回空列表，不查库）
     * @param limit  本次领取上限
     * @return 待执行任务列表
     */
    List<WbPipelineTask> listQueuedByStages(List<PipelineStage> stages, int limit);

    /**
     * 条件更新领任务：QUEUED → RUNNING + 开始时间（防多实例重复领取）。
     *
     * @param id 任务 ID
     * @return 受影响行数；=1 才表示领取成功、可执行
     */
    int claim(Long id);

    /**
     * 成功运行回写产物引用。
     *
     * @param taskId    任务 ID
     * @param productId 产物 ID（wb_knowledge_pipeline_product.id）
     */
    void updateProductId(Long taskId, Long productId);
}
