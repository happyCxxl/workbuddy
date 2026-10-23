package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.task.support.TaskRunnerSupport;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.AssembleOutcome;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.DocumentAssemblerPort;
import com.workbuddy.worker.structure.StructureProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 统一组装任务执行器（STRUCTURE 环节）：领任务 → 读上游 ParseResult 产物 →
 * 调 worker 组装管线 → 写 UnifiedDocument 产物 + STRUCTURE 产物行（upstream 链）→
 * 子步骤记录 → 回写终态（空树 FAILED / 其余 SUCCESS）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructureTaskRunner {

    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final WbPipelineProductDbService pipelineProductDbService;
    private final FileStorage fileStorage;
    private final DocumentAssemblerPort documentAssembler;
    private final StructureProperties structureProperties;
    private final StepLogPersistence stepLogPersistence;
    private final ProductPersistence productPersistence;
    private final UpstreamProductGate upstreamProductGate;

    /**
     * 执行单个组装任务（由消费循环提交，外层看门狗负责超时）。
     *
     * @param taskId 任务 ID
     */
    public void run(Long taskId) {
        WbPipelineTask task = TaskRunnerSupport.claim(pipelineTaskDbService, taskId);
        if (NullUtil.isNull(task)) {
            return;
        }
        log.info("===> StructureTaskRunner 领取组装任务, taskId={}, fileResultId={}",
                taskId, task.getFileResultId());
        try {
            // 上游解析产物：只认任务行上的显式引用，缺引用按任务失败处理
            WbPipelineProduct parseProduct = resolveParseProduct(task);
            if (NullUtil.isNull(parseProduct)) {
                log.warn("===> StructureTaskRunner 组装失败：任务未指定上游解析产物, taskId={}, fileResultId={}",
                        taskId, task.getFileResultId());
                finishFailed(taskId, PipelineTaskErrorCode.TASK_UPSTREAM_MISSING.name(),
                        "任务未指定上游解析产物");
                return;
            }
            // 下游门禁：产出任务已成功或部分成功才继续，未成功的运行留下的产物不读也不落库
            upstreamProductGate.requireConsumable(parseProduct);
            ParseResult parseResult;
            try {
                byte[] content = fileStorage.getObject(ObjectRef.ofProduct(parseProduct));
                parseResult = JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), ParseResult.class);
            } catch (Exception e) {
                log.warn("===> StructureTaskRunner 读取上游解析产物失败, taskId={}, artifactId={}", taskId, parseProduct.getArtifactId(), e);
                finishFailed(taskId, PipelineTaskErrorCode.STRUCTURE_UPSTREAM_UNREADABLE.name(),
                        "上游解析产物读取失败: " + TaskRunnerSupport.failureText(e));
                return;
            }
            if (NullUtil.isNull(parseResult)) {
                log.warn("===> StructureTaskRunner 组装失败：上游解析产物反序列化失败, taskId={}, artifactId={}",
                        taskId, parseProduct.getArtifactId());
                finishFailed(taskId, PipelineTaskErrorCode.STRUCTURE_UPSTREAM_UNREADABLE.name(),
                        "上游解析产物反序列化失败");
                return;
            }

            AssembleContext context = new AssembleContext();
            context.setTaskId(taskId);
            context.setFileResultId(task.getFileResultId());
            context.setProperties(structureProperties);
            AssembleOutcome outcome = documentAssembler.assemble(parseResult, context);

            TaskRunnerSupport.complete(pipelineTaskDbService, stepLogPersistence, taskId, outcome,
                    () -> persistProduct(task, parseProduct, outcome),
                    this::finishFailed);
        } catch (Exception e) {
            log.error("===> StructureTaskRunner 组装任务执行异常, taskId={}", taskId, e);
            finishFailed(taskId, errorCodeOf(e), TaskRunnerSupport.failureText(e));
        }
    }

    /** 失败归口的任务错误码：上游文件/结果缺失按"上游不可读"归口，存储不一致走共享映射，其余按组装执行异常兜底 */
    private String errorCodeOf(Exception e) {
        if (e instanceof WorkbuddyException knowledge && isUpstreamMissing(knowledge.getErrorCode())) {
            return PipelineTaskErrorCode.STRUCTURE_UPSTREAM_UNREADABLE.name();
        }
        return TaskRunnerSupport.failureCode(e, PipelineTaskErrorCode.STRUCTURE_FAILED);
    }

    /** 上游缺失判定：产物所在文件或文件结果已经不存在 */
    private boolean isUpstreamMissing(ErrorCode errorCode) {
        return errorCode == ErrorCode.FILE_NOT_FOUND || errorCode == ErrorCode.FILE_RESULT_NOT_FOUND;
    }

    /** 上游解析产物解析：任务指定 upstreamProductId 优先，查不到或缺省回退该环节最新产物。 */
    private WbPipelineProduct resolveParseProduct(WbPipelineTask task) {
        return TaskRunnerSupport.resolveUpstreamProduct(pipelineProductDbService, task);
    }

    private void persistProduct(WbPipelineTask task, WbPipelineProduct parseProduct, AssembleOutcome outcome) {
        UnifiedDocument document = Objects.requireNonNull(outcome.getDocument(), "组装结果为空");
        // 组装环节没有独立能力快照：产物行该列只承载 schemaVersion（解析环节的能力快照在 documentInfo 里）
        WbPipelineProduct product = productPersistence.persist(task, PipelineStage.STRUCTURE, parseProduct.getId(),
                JsonUtil.toJsonStr(Map.of("schemaVersion", UnifiedDocument.SCHEMA_VERSION)), document);
        stepLogPersistence.save(task.getId(), outcome.getStepLogs());
        TaskRunnerSupport.logPersisted(log, "StructureTaskRunner 组装完成", task, task.getFileResultId(), outcome,
                product.getArtifactId());
    }

    private void finishFailed(Long taskId, String errorCode, String errorMsg) {
        TaskRunnerSupport.finishFailed(pipelineTaskDbService, taskId, errorCode, errorMsg);
    }
}
