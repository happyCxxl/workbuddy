package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KbPipelineProductDbService;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.task.support.TaskRunnerSupport;
import com.workbuddy.common.domain.entity.KbFileResult;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.domain.preprocess.PreprocessOutcome;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.preprocessing.PreprocessContext;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.PreprocessorPort;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategyParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 预处理任务执行器（消费循环调用）：领任务 → 读任务策略快照（触发时固定）→ 读上游 STRUCTURE 产物
 * （反序列化 UnifiedDocument）→ 调 worker 预处理管线 → 写产物存储 + 落库（product/9 条 step_log）→
 * 回写任务终态。原文 UnifiedDocument 只读零改动。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreprocessTaskRunner {

    private final KbPipelineTaskDbService pipelineTaskDbService;
    private final KbFileResultDbService fileResultDbService;
    private final KbPipelineProductDbService pipelineProductDbService;
    private final FileStorage fileStorage;
    private final PreprocessorPort preprocessor;
    private final PreprocessProperties preprocessProperties;
    private final PreprocessStrategyParser strategyParser;
    private final StepLogPersistence stepLogPersistence;
    private final ProductPersistence productPersistence;
    private final UpstreamProductGate upstreamProductGate;

    /**
     * 执行单个预处理任务（由消费循环提交，外层看门狗负责超时）。
     */
    public void run(Long taskId) {
        TaskRunnerSupport.Claimed claimed = TaskRunnerSupport.claimFileResult(pipelineTaskDbService,
                fileResultDbService, taskId, PipelineTaskErrorCode.PREPROCESS_FAILED, this::finishFailed);
        if (NullUtil.isNull(claimed)) {
            return;
        }
        KbPipelineTask task = claimed.task();
        KbFileResult fileResult = claimed.fileResult();
        log.info("===> PreprocessTaskRunner 领取预处理任务, taskId={}, fileResultId={}",
                taskId, task.getFileResultId());
        try {
            // 上游组装产物：只认任务行上的显式引用，缺引用按任务失败处理
            // 上游组装产物：任务行缺引用与产出任务未成功都在助手里拒绝（门禁：未成功的运行不读也不落库）
            KbPipelineProduct structureProduct = TaskRunnerSupport.requireUpstream(pipelineProductDbService,
                    upstreamProductGate, task, taskId, "任务未指定上游统一结构产物", this::finishFailed);
            if (NullUtil.isNull(structureProduct)) {
                return;
            }

            // 策略只来自策略版本表：任务行快照缺失或损坏即失败，不用代码里的默认策略兜底
            PreprocessStrategy strategy = TaskRunnerSupport.parseStrategy(task, taskId,
                    PipelineTaskErrorCode.PREPROCESS_FAILED, "任务未带预处理策略快照或快照已损坏，无法预处理",
                    strategyParser::parse, this::finishFailed);
            if (NullUtil.isNull(strategy)) {
                return;
            }
            UnifiedDocument document;
            try {
                byte[] content = fileStorage.getObject(ObjectRef.ofProduct(structureProduct));
                document = JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), UnifiedDocument.class);
            } catch (Exception e) {
                log.warn("读取上游统一结构产物失败, taskId={}, artifactId={}", taskId, structureProduct.getArtifactId(), e);
                finishFailed(taskId, PipelineTaskErrorCode.PREPROCESS_EMPTY.name(),
                        "上游统一结构产物读取失败: " + e.getMessage());
                return;
            }
            if (NullUtil.isNull(document)) {
                log.warn("===> PreprocessTaskRunner 预处理失败：上游统一结构产物反序列化失败, taskId={}, artifactId={}",
                        taskId, structureProduct.getArtifactId());
                finishFailed(taskId, PipelineTaskErrorCode.PREPROCESS_EMPTY.name(), "上游统一结构产物反序列化失败");
                return;
            }

            PreprocessContext context = new PreprocessContext();
            context.setDocument(document);
            context.setStrategy(strategy);
            context.setProperties(preprocessProperties);
            context.setFileResultId(fileResult.getId());
            context.setUpstreamProductRef(structureProduct.getId());

            PreprocessOutcome outcome = preprocessor.preprocess(context);
            TaskRunnerSupport.complete(pipelineTaskDbService, stepLogPersistence, taskId, outcome,
                    () -> persistProduct(task, fileResult, structureProduct, strategy, outcome),
                    this::finishFailed);
        } catch (Exception e) {
            log.error("预处理任务执行异常, taskId={}", taskId, e);
            finishFailed(taskId, TaskRunnerSupport.failureCode(e, PipelineTaskErrorCode.PREPROCESS_FAILED),
                    String.valueOf(e.getMessage()));
        }
    }

    /** 写产物存储 + 阶段产物引用 + 子步骤记录（成功/PARTIAL_SUCCESS 路径） */
    private void persistProduct(KbPipelineTask task, KbFileResult fileResult, KbPipelineProduct structureProduct,
                                PreprocessStrategy strategy, PreprocessOutcome outcome) {
        Objects.requireNonNull(outcome.getView(), "预处理结果为空");
        KbPipelineProduct product = productPersistence.persist(task, PipelineStage.PREPROCESS,
                structureProduct.getId(), JsonUtil.toJsonStr(strategy), outcome.getView());
        stepLogPersistence.save(task.getId(), outcome.getStepLogs());
        TaskRunnerSupport.logPersisted(log, "PreprocessTaskRunner 预处理完成", task, fileResult.getId(), outcome,
                product.getArtifactId());
    }

    private void finishFailed(Long taskId, String errorCode, String errorMsg) {
        log.warn("===> PreprocessTaskRunner 预处理任务失败, taskId={}, errorCode={}, errorMsg={}",
                taskId, errorCode, errorMsg);
        TaskRunnerSupport.finishFailed(pipelineTaskDbService, taskId, errorCode, errorMsg);
    }
}
