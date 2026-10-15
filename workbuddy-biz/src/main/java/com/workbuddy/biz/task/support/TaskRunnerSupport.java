package com.workbuddy.biz.task.support;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KbPipelineProductDbService;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.common.domain.entity.KbFileResult;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.domain.task.StageOutcome;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import java.util.function.Function;

/**
 * 环节任务执行器共用助手：**领任务的准入判断**、**上游产物解析与门禁**、**策略快照解析**、
 * **终态收尾**各环节完全同构，集中在这里，执行器只管自己的管线调用与落库。
 *
 * <p>手动逐环节口径：成功后停在终态，不自动触发下游。
 *
 * @author cxxl
 */
@Slf4j
public final class TaskRunnerSupport {

    /** 失败原因入库前的截断长度（字符） */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private TaskRunnerSupport() {
    }

    /**
     * 领任务：不存在、非 QUEUED、或条件更新（{@code claim}）未抢到时返回 null；
     * 条件更新是多实例下的互斥闸门。
     *
     * @param pipelineTaskDbService 任务数据访问
     * @param taskId                任务 ID
     * @return 领取到的任务；未领取到返回 null
     */
    public static KbPipelineTask claim(KbPipelineTaskDbService pipelineTaskDbService, Long taskId) {
        KbPipelineTask task = pipelineTaskDbService.getById(taskId);
        if (NullUtil.isNull(task)
                || !PipelineTaskStatus.QUEUED.name().equals(task.getStatus())) {
            return null;
        }
        return pipelineTaskDbService.claim(taskId) == 1 ? task : null;
    }

    /** 领任务 + 取文件结果的结果对（领不到或文件结果缺失时为 null）。 */
    public record Claimed(KbPipelineTask task, KbFileResult fileResult) {
    }

    /**
     * 领任务并取文件结果：任务未领到（不存在、非 QUEUED、未抢到）返回 null；
     * 文件结果不存在按该环节失败收尾并返回 null。
     *
     * @param pipelineTaskDbService 任务数据访问
     * @param fileResultDbService   文件结果数据访问
     * @param taskId                任务 ID
     * @param failureCode           该环节的兜底任务错误码
     * @param finisher              该执行器的失败回写
     * @return 领取到的任务与文件结果；未领取到或文件结果缺失时返回 null
     */
    public static Claimed claimFileResult(KbPipelineTaskDbService pipelineTaskDbService,
                                          KbFileResultDbService fileResultDbService, Long taskId,
                                          PipelineTaskErrorCode failureCode, FailedFinisher finisher) {
        KbPipelineTask task = claim(pipelineTaskDbService, taskId);
        if (NullUtil.isNull(task)) {
            return null;
        }
        KbFileResult fileResult = fileResultDbService.getById(task.getFileResultId());
        if (NullUtil.isNull(fileResult)) {
            finisher.finish(taskId, failureCode.name(), "文件结果不存在: " + task.getFileResultId());
            return null;
        }
        return new Claimed(task, fileResult);
    }

    /**
     * 上游产物解析：只认任务行上显式指定的 {@code upstreamProductId}（触发时确定，0 表示没有上游），
     * 查不到返回 null。不做「取该环节最新产物」这类按时间猜的兜底 —— 引用缺失按任务失败处理。
     *
     * @param productDbService 产物数据访问
     * @param task             当前任务
     * @return 上游产物；没有上游或查不到时返回 null
     */
    public static KbPipelineProduct resolveUpstreamProduct(KbPipelineProductDbService productDbService,
                                                           KbPipelineTask task) {
        Long upstreamProductId = upstreamOf(task);
        return NullUtil.isNull(upstreamProductId) ? null : productDbService.getById(upstreamProductId);
    }

    /**
     * 上游产物门禁 + 存在性：任务行引用缺失按该环节失败收尾；产出任务未成功（非
     * SUCCESS/PARTIAL_SUCCESS）由门禁直接拒绝。校验不过时助手上报终态并返回 null，调用方直接 return。
     *
     * @param productDbService 产物数据访问
     * @param gate             上游产物门禁
     * @param task             当前任务
     * @param taskId           任务 ID（回写终态用）
     * @param missingText      缺上游引用时的失败文案（按环节给）
     * @param finisher         该执行器的失败回写
     * @return 上游产物；缺引用时返回 null
     */
    public static KbPipelineProduct requireUpstream(KbPipelineProductDbService productDbService,
                                                    UpstreamProductGate gate, KbPipelineTask task, Long taskId,
                                                    String missingText, FailedFinisher finisher) {
        KbPipelineProduct product = resolveUpstreamProduct(productDbService, task);
        if (NullUtil.isNotNull(product)) {
            gate.requireConsumable(product);
            return product;
        }
        finisher.finish(taskId, PipelineTaskErrorCode.TASK_UPSTREAM_MISSING.name(), missingText);
        return null;
    }

    /**
     * 策略快照 → 策略对象：快照缺失或损坏按该环节失败收尾（策略只来自策略版本表，不用代码里的默认兜底）。
     * 解析失败时助手上报终态并返回 null，调用方直接 return。
     *
     * @param task         当前任务
     * @param taskId       任务 ID（回写终态用）
     * @param strategyCode 该环节的兜底任务错误码
     * @param failureText  失败文案（按环节给）
     * @param parse        按快照解析策略（各环节解析器）
     * @param finisher     该执行器的失败回写
     * @param <T>          策略类型
     * @return 解析后的策略；快照缺失或损坏时返回 null
     */
    public static <T> T parseStrategy(KbPipelineTask task, Long taskId, PipelineTaskErrorCode strategyCode,
                                      String failureText, Function<String, T> parse, FailedFinisher finisher) {
        try {
            return parse.apply(task.getStrategySnapshot());
        } catch (RuntimeException e) {
            log.warn("任务策略快照缺失或损坏, taskId={}, stage={}", taskId, task.getStage(), e);
            finisher.finish(taskId, strategyCode.name(), failureText);
            return null;
        }
    }

    /**
     * 任务行上的上游产物 ID：0 与 null 都表示"没有上游"（解析是链路起点），统一返回 null 表示没有。
     *
     * @param task 当前任务
     * @return 上游产物 ID；没有上游时返回 null
     */
    public static Long upstreamOf(KbPipelineTask task) {
        Long upstreamProductId = NullUtil.isNull(task) ? null : task.getUpstreamProductId();
        return NullUtil.isNull(upstreamProductId) || upstreamProductId == 0L ? null : upstreamProductId;
    }

    /**
     * 任务行上的策略版本 ID：0 与 null 都表示"该环节无策略"（解析/组装），统一返回 null 表示没有。
     *
     * @param task 当前任务
     * @return 策略版本 ID；该环节无策略时返回 null
     */
    public static Long strategyVersionOf(KbPipelineTask task) {
        Long strategyVersionId = NullUtil.isNull(task) ? null : task.getStrategyVersionId();
        return NullUtil.isNull(strategyVersionId) || strategyVersionId == 0L ? null : strategyVersionId;
    }

    /**
     * 失败归口的任务错误码：链的数据源与当前启用的数据源不一致（40453）单独归口为
     * {@link PipelineTaskErrorCode#TASK_STORAGE_MISMATCH}，其余情况用该环节的兜底码。
     *
     * <p>本方法读异常自身的错误码判定，不解析失败文案：任务行的 {@code error_code} 可以直接看出存储不一致。
     *
     * @param e        执行器捕获到的异常
     * @param fallback 该环节的兜底任务错误码
     * @return 落库用的任务错误码码值
     */
    public static String failureCode(Exception e, PipelineTaskErrorCode fallback) {
        if (e instanceof KnowledgeException knowledge
                && knowledge.getErrorCode() == ErrorCode.STORAGE_TYPE_MISMATCH) {
            return PipelineTaskErrorCode.TASK_STORAGE_MISMATCH.name();
        }
        return fallback.name();
    }

    /**
     * 失败文案：业务异常用它自己的原因；其它异常带类名；消息为空时退化为类名（不落字面量 null）。
     *
     * @param e 执行器捕获到的异常
     * @return 落库用的失败原因
     */
    public static String failureText(Exception e) {
        String message = StrUtil.trimToNull(e.getMessage());
        if (e instanceof KnowledgeException) {
            return NullUtil.isNull(message) ? e.getClass().getSimpleName() : message;
        }
        return NullUtil.isNull(message) ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 失败回写：把任务置 FAILED 并落失败原因（原因统一截断到 {@value #MAX_ERROR_MESSAGE_LENGTH} 字符，
     * 空串按 null 落库）。
     *
     * @param pipelineTaskDbService 任务数据访问
     * @param taskId                任务 ID
     * @param errorCode             错误码（各环节自己的枚举码值）
     * @param errorMsg              失败原因（可空；超长截断，空串按 null 落库）
     */
    public static void finishFailed(KbPipelineTaskDbService pipelineTaskDbService, Long taskId,
                                    String errorCode, String errorMsg) {
        pipelineTaskDbService.finish(taskId, PipelineTaskStatus.FAILED, errorCode,
                StrUtil.isBlank(errorMsg) ? null : StrUtil.maxLength(errorMsg, MAX_ERROR_MESSAGE_LENGTH));
    }

    /** 终态收尾：按建议状态分派成功/失败路径。 */
    public static void complete(KbPipelineTaskDbService pipelineTaskDbService,
                                StepLogPersistence stepLogPersistence, Long taskId,
                                StageOutcome outcome, Runnable onSuccess, FailedFinisher onFailure) {
        switch (outcome.getSuggestedStatus()) {
            case SUCCESS, PARTIAL_SUCCESS -> {
                onSuccess.run();
                pipelineTaskDbService.finish(taskId, outcome.getSuggestedStatus(), null, null);
            }
            default -> {
                stepLogPersistence.save(taskId, outcome.getStepLogs());
                onFailure.finish(taskId, outcome.getErrorCode(), outcome.getErrorMsg());
            }
        }
    }

    /**
     * 完成日志（落库收尾共用）：label 传完整前缀（如 "ParseTaskRunner 解析完成"），logger 用调用方自己的，
     * 日志类别与文案仍留在各执行器上。
     */
    public static void logPersisted(Logger log, String label, KbPipelineTask task, Long fileResultId,
                                    StageOutcome outcome, String artifactId) {
        log.info("===> {}, taskId={}, fileResultId={}, status={}, artifactId={}",
                label, task.getId(), fileResultId, outcome.getSuggestedStatus().name(), artifactId);
    }

    /** 失败回写动作（各执行器自持回写口径）。 */
    @FunctionalInterface
    public interface FailedFinisher {

        void finish(Long taskId, String errorCode, String errorMsg);
    }
}
