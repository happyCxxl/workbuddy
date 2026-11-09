package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.runner.asset.FileStorageParseAssetSink;
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
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.config.FileProperties;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.parser.CancellationToken;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.ParsePipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析任务执行器（消费循环调用）：领任务 → 读文档输入环节建档数据 → 开流调 worker 解析管线 →
 * 写产物存储 + 落库（product/step_log）→ 回写任务终态。
 * 状态回写只更新 wb_knowledge_pipeline_task 自身与产物引用，不回写领域规则。
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
    private final FileProperties fileProperties;
    private final StepLogPersistence stepLogPersistence;

    /** 执行中的解析任务取消令牌（taskId → token） */
    private final Map<Long, CancellationToken> runningTokens = new ConcurrentHashMap<>();

    /**
     * 执行单个解析任务（由消费循环提交，外层看门狗负责超时）。
     *
     * @param taskId 任务 ID
     */
    public void run(Long taskId) {
        WbPipelineTask task = TaskRunnerSupport.claim(pipelineTaskDbService, taskId);
        if (NullUtil.isNull(task)) {
            return;
        }
        CancellationToken token = new CancellationToken();
        runningTokens.put(taskId, token);
        try {
            // 查询上传的文件记录结果
            WbFileResult fileResult = fileResultDbService.getById(task.getFileResultId());
            if (NullUtil.isNull(fileResult)) {
                finishFailed(taskId, PipelineTaskErrorCode.PARSE_INPUT_UNREADABLE.name(),
                        "知识库文件不存在: " + task.getFileResultId());
                return;
            }
            // 获取上传的源文件
            WbSourceFile sourceFile = sourceFileDbService.getById(fileResult.getSourceFileId());
            if (NullUtil.isNull(sourceFile)) {
                finishFailed(taskId, PipelineTaskErrorCode.PARSE_INPUT_UNREADABLE.name(),
                        "来源文件不存在: " + fileResult.getSourceFileId());
                return;
            }
            if (NullUtil.isNotNull(sourceFile.getFileSize()) && fileProperties.getMaxSize() > 0
                    && sourceFile.getFileSize() > fileProperties.getMaxSize()) {
                finishFailed(taskId, PipelineTaskErrorCode.PARSE_TOO_LARGE.name(),
                        "文件过大: " + sourceFile.getFileSize() + " 字节，超过上限 "
                                + fileProperties.getMaxSize() + " 字节");
                return;
            }
            log.info("===> ParseTaskRunner 开始解析, taskId={}, knowledgeFileId={}, fileId={}, mimeType={}",
                    taskId, fileResult.getId(), sourceFile.getFileId(), sourceFile.getMimeType());

            // 构建解析上下文
            ParseContext context = new ParseContext();
            context.setKnowledgeFileId(fileResult.getId());
            context.setFileRef(toFileRef(sourceFile));
            context.setProperties(parseProperties);
            context.setCancellationToken(token);
            context.setAssetSink(new FileStorageParseAssetSink(fileStorage));

            InputStream in;
            try {
                in = fileStorage.open(sourceFile.getFileId());
            } catch (WorkbuddyException e) {
                if (e.getErrorCode() == ErrorCode.STORAGE_TYPE_MISMATCH) {
                    throw e;
                }
                finishFailed(taskId, PipelineTaskErrorCode.PARSE_INPUT_UNREADABLE.name(),
                        e.getErrorCode() == ErrorCode.FILE_NOT_FOUND
                                ? "文件不存在或不可读（提交后可能被删除）"
                                : "无法打开文件流: " + TaskRunnerSupport.failureText(e));
                return;
            } catch (Exception e) {
                finishFailed(taskId, PipelineTaskErrorCode.PARSE_INPUT_UNREADABLE.name(),
                        "无法打开文件流: " + TaskRunnerSupport.failureText(e));
                return;
            }

            ParseOutcome outcome;
            try (InputStream closeable = in) {
                context.setInputStream(closeable);
                outcome = parsePipeline.run(context);
            }

            TaskRunnerSupport.complete(pipelineTaskDbService, stepLogPersistence, taskId, outcome,
                    () -> persistProduct(task, fileResult, outcome),
                    this::finishFailed);
        } catch (Exception e) {
            log.error("===> ParseTaskRunner 解析任务执行异常, taskId={}", taskId, e);
            finishFailed(taskId, TaskRunnerSupport.failureCode(e, PipelineTaskErrorCode.PARSE_FAILED),
                    TaskRunnerSupport.failureText(e));
        } finally {
            runningTokens.remove(taskId);
        }
    }

    /**
     * 取消执行中的解析任务：看门狗超时/停机时调用，令牌置位后解析器在页边界退出。
     *
     * @param taskId 任务 ID
     */
    public void cancel(Long taskId) {
        CancellationToken token = runningTokens.get(taskId);
        if (NullUtil.isNotNull(token)) {
            token.cancel();
        }
    }

    /** 写产物存储 + 阶段产物引用 + 子步骤记录（成功/PARTIAL_SUCCESS 路径） */
    private void persistProduct(WbPipelineTask task, WbFileResult fileResult, ParseOutcome outcome) {
        WbPipelineTask current = pipelineTaskDbService.getById(task.getId());
        if (NullUtil.isNull(current) || !PipelineTaskStatus.RUNNING.name().equals(current.getStatus())) {
            log.warn("===> ParseTaskRunner 任务已非 RUNNING，跳过产物写入, taskId={}, status={}",
                    task.getId(), NullUtil.isNull(current) ? null : current.getStatus());
            return;
        }
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
