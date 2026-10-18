package com.workbuddy.biz.service.support;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 环节统计公共口径：读产物本体、耗时、结论文案的前置判断，各环节的统计支持类共用。
 *
 * <p>纯读取，不落库、不改产物；各环节自己的口径（元素归类、异常项陈述）留在各自的类里。
 *
 * @author cxxl
 */
@Slf4j
public final class StatsSupport {

    private StatsSupport() {
    }

    /**
     * 读产物本体。存储类读取失败（40454 / 40455）原样抛出；对象位置为空、其余读取失败或
     * JSON 解析失败记日志并返回 null。
     *
     * @param fileStorage 对象存储
     * @param ref         产物对象位置（含存储类型与桶名），可空
     * @param type        产物本体类型
     * @return 产物本体；取不到返回 null
     * @throws KnowledgeException 对象在所属存储中不存在（40454）或记录所属后端未配置（40455）
     */
    public static <T> T readArtifact(FileStorage fileStorage, ObjectRef ref, Class<T> type) {
        if (NullUtil.isNull(fileStorage) || NullUtil.isNull(ref)) {
            return null;
        }
        try {
            byte[] content = fileStorage.getObject(ref);
            if (NullUtil.isNull(content)) {
                return null;
            }
            return JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            KnowledgeException storageFailure = storageFailureOf(e);
            if (NullUtil.isNotNull(storageFailure)) {
                throw storageFailure;
            }
            log.warn("产物读取失败, object={}", ref.path(), e);
            return null;
        }
    }

    /**
     * 存储类读取失败识别：对象在所属存储中不存在（40454）或记录所属后端未配置（40455）。
     *
     * <p>详情类接口对读取失败一律留空、不阻断，这两类要向上抛出，由统一异常处理给出明确错误码。
     *
     * @param e 读取过程中抛出的异常
     * @return 命中则返回该异常，可原样抛出；其余情况返回 null
     */
    public static KnowledgeException storageFailureOf(Exception e) {
        if (!(e instanceof KnowledgeException failure)) {
            return null;
        }
        ErrorCode code = failure.getErrorCode();
        return code == ErrorCode.STORAGE_OBJECT_MISSING || code == ErrorCode.STORAGE_BACKEND_UNCONFIGURED
                ? failure : null;
    }

    /**
     * 结论文案的前置判断：失败取失败原因，非成功 / 部分成功不陈述结论。
     *
     * @param errorMsg 失败原因
     * @param status   任务状态
     * @param failText 失败原因缺失时的兜底文案（按环节给）
     * @param body     统计陈述：统计缺失时由它返回 null
     * @return 结论文案；无可陈述内容时返回 null
     */
    public static String summaryOf(String errorMsg, String status, String failText, Supplier<String> body) {
        if (PipelineTaskStatus.FAILED.name().equals(status)) {
            return StrUtil.blankToDefault(errorMsg, failText);
        }
        if (!PipelineTaskStatus.SUCCESS.name().equals(status)
                && !PipelineTaskStatus.PARTIAL_SUCCESS.name().equals(status)) {
            return null;
        }
        return body.get();
    }

    /** 本次运行耗时：起止时间齐全时相减，缺失返回 null */
    public static Long durationMs(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (NullUtil.isNull(startedAt) || NullUtil.isNull(finishedAt)) {
            return null;
        }
        return Duration.between(startedAt, finishedAt).toMillis();
    }

    /**
     * 环节统计与摘要并入执行树节点：摘要写 {@code stageSummary}，统计并入通用 {@code stats}。
     *
     * <p>通用 stats 里可能已有该环节由库表聚合出来的键（切片 chunkCount、预处理 matched/changed），
     * 这里是"并入"而不是整份替换 —— 替换会把先落的通用键覆盖掉。
     *
     * @param node    执行树节点
     * @param stats   本环节统计（**可空**：产物不可读时不并入）
     * @param summary 本环节摘要（可空）
     */
    public static void mergeNodeStats(LineageNodeVO node, Map<String, Object> stats, String summary) {
        node.setStageSummary(summary);
        if (NullUtil.isNull(stats)) {
            return;
        }
        Map<String, Object> merged = NullUtil.isNull(node.getStats())
                ? new LinkedHashMap<>() : new LinkedHashMap<>(node.getStats());
        merged.putAll(stats);
        node.setStats(merged);
    }
}
