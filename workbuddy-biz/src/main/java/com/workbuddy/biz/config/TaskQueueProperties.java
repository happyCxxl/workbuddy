package com.workbuddy.biz.config;

import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.NullUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 任务队列配置（knowledge.task 前缀）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.task")
public class TaskQueueProperties {

    /** 未配置环节的回退超时 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** 任务唤醒队列 key（Redis List） */
    private String queueKey = "knowledge:task:queue";

    /** 消费侧 BRPOP 阻塞时长 */
    private Duration wakeupTimeout = Duration.ofSeconds(5);

    /** 任务执行线程池并发 */
    private int concurrency = 2;

    /** 每次叫醒扫库领批上限（超出留待下次叫醒） */
    private int claimBatchSize = 10;

    /** 启动补偿：QUEUED 且创建时间早于此阈值才补投 */
    private Duration compensateThreshold = Duration.ofMinutes(5);

    /** 低频兜底对账开关（默认开启：入库未入队的滞留任务由它兜住） */
    private boolean lowFreqSweepEnabled = true;

    /** 低频兜底周期（配合 5 分钟补投门槛，最长滞留约 6 分钟） */
    private Duration lowFreqSweepInterval = Duration.ofMinutes(1);

    /** 补偿/兜底分布式锁持有时长（SET NX EX） */
    private Duration sweepLockTtl = Duration.ofSeconds(30);

    /** 停机时等待在跑任务结束的上限；到点中断仍未结束的任务 */
    private Duration shutdownWait = Duration.ofSeconds(30);

    /**
     * 单任务超时：按环节配置；未配置环节回退全局默认。
     *
     * <p>键是 {@link PipelineStage} 枚举：配置里写错环节名（如 `pars`）在绑定期直接失败，不再静默回退默认值。
     * 配置键仍写 kebab 形式（`build-index`），宽松绑定认它。
     */
    private Map<PipelineStage, Duration> timeout = new HashMap<>(Map.of(
            PipelineStage.PARSE, Duration.ofMinutes(5),
            PipelineStage.STRUCTURE, Duration.ofMinutes(5),
            PipelineStage.PREPROCESS, Duration.ofMinutes(5),
            PipelineStage.CHUNK, Duration.ofMinutes(5),
            PipelineStage.EMBED, Duration.ofMinutes(10),
            PipelineStage.BUILD_INDEX, Duration.ofMinutes(10)));

    /**
     * 取指定环节的单任务超时：环节标识支持枚举名（BUILD_INDEX）与配置键（build-index）两种写法；
     * 环节为空、认不出，或该环节未配置，一律回退默认 5 分钟。
     */
    public Duration timeoutOf(String stage) {
        PipelineStage parsed = parseStage(stage);
        if (NullUtil.isNull(parsed)) {
            return DEFAULT_TIMEOUT;
        }
        Duration configured = timeout.get(parsed);
        return NullUtil.isNull(configured) ? DEFAULT_TIMEOUT : configured;
    }

    /** 环节标识归一化：去空格、转大写、连字符转下划线后按枚举名匹配，认不出返回 null */
    private static PipelineStage parseStage(String stage) {
        if (NullUtil.isNull(stage) || stage.isBlank()) {
            return null;
        }
        String normalized = stage.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (PipelineStage candidate : PipelineStage.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
