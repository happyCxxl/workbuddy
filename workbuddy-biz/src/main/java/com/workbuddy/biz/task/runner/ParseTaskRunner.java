package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.support.TaskRunnerSupport;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.ParsePipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Objects;

/**
 * 解析任务执行器（消费循环调用）：领任务 → 读文档输入环节建档数据 → 开流调 worker 解析管线 →
 * 写产物存储 + 落库（product/step_log）→ 回写任务终态。
 * 状态回写只更新 wb_knowledge_pipeline_task 自身与产物引用，不回写领域规则。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParseTaskRunner {

    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final WbFileResultDbService fileResultDbService;
    private final WbSourceFileDbService sourceFileDbService;
    private final ProductPersistence productPersistence;
    private final FileStorage fileStorage;
    private final ParsePipeline parsePipeline;
    private final ParseProperties parseProperties;
    private final StepLogPersistence stepLogPersistence;

    /**
     * 执行单个解析任务（由消费循环提交，外层看门狗负责超时）。
     */
    public void run(Long taskId) {
        WbPipelineTask task = TaskRunnerSupport.claim(pipelineTaskDbService, taskId);
        if (NullUtil.isNull(task)) {
            return;
        }
        try {
            WbFileResult fileResult = fileResultDbService.getById(task.getFileResultId());
            if (NullUtil.isNull(fileResult)) {
                finishFailed(taskId, PipelineTaskErrorCode.PARSE_FAILED.name(),
                        "文件结果不存在: " + task.getFileResultId());
                return;
            }
            WbSourceFile sourceFile = sourceFileDbService.getById(fileResult.getSourceFileId());
            if (NullUtil.isNull(sourceFile)) {
                finishFailed(taskId, PipelineTaskErrorCode.PARSE_FAILED.name(),
                        "来源文件不存在: " + fileResult.getSourceFileId());
                return;
            }
            log.info("===> ParseTaskRunner 开始解析, taskId={}, fileResultId={}, fileId={}, mimeType={}",
                    taskId, fileResult.getId(), sourceFile.getFileId(), sourceFile.getMimeType());

            ParseContext context = new ParseContext();
            context.setFileResultId(fileResult.getId());
            context.setFileRef(toFileRef(sourceFile));
            context.setProperties(parseProperties);

            ParseOutcome outcome;
            try (InputStream in = fileStorage.open(sourceFile.getFileId())) {
                context.setInputStream(in);
                outcome = parsePipeline.run(context);
            } catch (KnowledgeException e) {
                if (e.getErrorCode() == ErrorCode.FILE_NOT_FOUND) {
                    finishFailed(taskId, PipelineTaskErrorCode.PARSE_FAILED.name(),
                            "文件不存在或不可读（提交后可能被删除）");
                    return;
                }
                throw e;
            }

            TaskRunnerSupport.complete(pipelineTaskDbService, stepLogPersistence, taskId, outcome,
                    () -> persistProduct(task, fileResult, outcome),
                    this::finishFailed);
        } catch (Exception e) {
            log.error("解析任务执行异常, taskId={}", taskId, e);
            finishFailed(taskId, TaskRunnerSupport.failureCode(e, PipelineTaskErrorCode.PARSE_FAILED),
                    TaskRunnerSupport.failureText(e));
        }
    }

    /** 写产物存储 + 阶段产物引用 + 子步骤记录（成功/PARTIAL_SUCCESS 路径） */
    private void persistProduct(WbPipelineTask task, WbFileResult fileResult, ParseOutcome outcome) {
        ParseResult parseResult = Objects.requireNonNull(outcome.getParseResult(), "解析结果为空");
        WbPipelineProduct product = productPersistence.persist(task, PipelineStage.PARSE, null,
                JsonUtil.toJsonStr(parseResult.getCapabilitySnapshot()), parseResult);
        stepLogPersistence.save(task.getId(), outcome.getStepLogs());
        TaskRunnerSupport.logPersisted(log, "ParseTaskRunner 解析完成", task, fileResult.getId(), outcome,
                product.getArtifactId());
    }

    /** 失败终态回写：统一留痕（带 taskId + 错误码）。 */
    private void finishFailed(Long taskId, String errorCode, String errorMsg) {
        log.warn("===> ParseTaskRunner 解析任务失败, taskId={}, errorCode={}, errorMsg={}",
                taskId, errorCode, errorMsg);
        TaskRunnerSupport.finishFailed(pipelineTaskDbService, taskId, errorCode, errorMsg);
    }

    private FileReference toFileRef(WbSourceFile sourceFile) {
        FileReference fileRef = new FileReference();
        fileRef.setFileId(sourceFile.getFileId());
        fileRef.setFileName(sourceFile.getFileName());
        fileRef.setSha256(sourceFile.getSha256());
        fileRef.setMimeType(sourceFile.getMimeType());
        return fileRef;
    }
}
