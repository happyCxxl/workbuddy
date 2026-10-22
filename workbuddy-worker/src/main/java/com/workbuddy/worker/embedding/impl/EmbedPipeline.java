package com.workbuddy.worker.embedding.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.embed.EmbedOutcome;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.embed.EmbeddingModel;
import com.workbuddy.common.enums.embed.EmbedRecordStatus;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.AiClient;
import com.workbuddy.model.ModelCatalogPort;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.embedding.EmbedContext;
import com.workbuddy.worker.embedding.EmbedWindowRules;
import com.workbuddy.worker.embedding.EmbedderPort;
import com.workbuddy.worker.embedding.check.ConsistencyChecker;
import com.workbuddy.worker.embedding.check.ConsistencyResult;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.embedding.template.InputTemplatePort;
import com.workbuddy.worker.pipeline.StepLogHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量化编排（模板方法模式）：固定流程骨架——前置校验 → 筛选 → 编码 →
 * 复用判定（账本回溯，同文件同策略，miss 清零即停）→ 分批模型调用（重试退避+失败批次隔离）→ 四关 → 组装。
 * 三条红线：不改内容（IdentityTemplate 原样编码）/ 不做兜底（前置校验报错）/ 不碰向量库（只落账本）。
 * 纯算法，不碰 DB/产物存储（复用候选账本由 biz 加载进 context；落库回写由 biz EmbedTaskRunner 编排）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedPipeline implements EmbedderPort {

    private final EmbedProperties properties;
    private final ConsistencyChecker checker;
    private final InputTemplatePort template;
    private final AiClient aiClient;
    private final ModelCatalogPort modelCatalogPort;

    /**
     * 一批文本的向量编码：交给模型门面，按请求里的模型名精确选模型。
     *
     * @param request 本批入参
     * @return 向量结果
     */
    private EmbeddingResult embedBatch(EmbeddingRequest request) {
        return aiClient.embed(request);
    }

    /**
     * 向量化环节入口：分批请求模型并落集合产物。
     *
     * @param context 环节上下文
     * @return 环节产出
     */
    @Override
    public EmbedOutcome embed(EmbedContext context) {
        EmbedOutcome outcome = new EmbedOutcome();
        EmbedStrategy strategy = context.getStrategy();
        if (NullUtil.isNull(strategy)) {
            outcome.fail(PipelineTaskErrorCode.EMBED_FAILED.name(), "任务未带向量化策略快照，无法向量化");
            return outcome;
        }
        log.info("===> EmbedPipeline 开始向量化, fileResultId={}, strategy={}",
                context.getFileResultId(), strategy.fullVersion());

        // ① 前置校验（写前闸门）：窗口兼容 / 模型目录
        long precheckStartedNanos = System.nanoTime();
        String precheckError = precheck(context, strategy, outcome);
        long precheckNanos = System.nanoTime() - precheckStartedNanos;
        if (NullUtil.isNotNull(precheckError)) {
            outcome.fail(PipelineTaskErrorCode.EMBED_MODEL_INCOMPATIBLE.name(), precheckError);
            return outcome;
        }

        // ② 筛选 + ③ 编码
        ChunkSet chunkSet = context.getChunkSet();
        if (NullUtil.isNull(chunkSet) || chunkSet.getChunks().isEmpty()) {
            outcome.fail(PipelineTaskErrorCode.EMBED_EMPTY.name(), "上游切片产物为空，请先触发切片");
            return outcome;
        }
        long encodeStartedNanos = System.nanoTime();
        List<EmbeddingRecord> records = encode(chunkSet, strategy);
        long encodeNanos = System.nanoTime() - encodeStartedNanos;
        List<EmbeddingRecord> candidates = records.stream()
                .filter(r -> !EmbedRecordStatus.SKIPPED.name().equals(r.getStatus()))
                .toList();
        if (candidates.isEmpty()) {
            outcome.fail(PipelineTaskErrorCode.EMBED_EMPTY.name(), "无任何可向量化切片（父片/空文本均被跳过）");
            return outcome;
        }

        // ④ 复用判定：同文件同策略账本新→旧回溯，miss 清零即停（cacheEnabled=OFF 跳过）
        int cachedCount = 0;
        long reuseNanos = 0;
        List<EmbeddingSet> history = ObjectUtil.defaultIfNull(context.getReuseCandidates(), List.of());
        if (strategy.cacheOn() && !history.isEmpty()) {
            long reuseStartedNanos = System.nanoTime();
            cachedCount = resolveReuse(candidates, history);
            reuseNanos = System.nanoTime() - reuseStartedNanos;
        }

        // ⑤ 分批模型调用 + ⑥ 四关
        List<EmbeddingRecord> pending = candidates.stream()
                .filter(r -> StrUtil.isBlank(r.getStatus()) || isNotCached(r))
                .toList();
        List<EmbeddingRecord> pendingList = new ArrayList<>(pending);
        long batchStartedNanos = System.nanoTime();
        BatchResult batchResult = invokeBatches(pendingList, strategy, outcome);
        long batchNanos = System.nanoTime() - batchStartedNanos;
        if (batchResult.consistencyRejected) {
            outcome.fail(PipelineTaskErrorCode.EMBED_CONSISTENCY_FAILED.name(),
                    "四关一致性校验不过（拒绝写入）: " + String.join("; ", batchResult.problems));
            return outcome;
        }

        // ⑦ 组装
        EmbeddingSet embeddingSet = assemble(context, strategy, records, cachedCount);
        outcome.setEmbeddingSet(embeddingSet);
        // 模型调用耗时 = 分批阶段墙钟 - 四关校验耗时（四关在批内触发，单独累计）
        outcome.setStepLogs(buildStepLogs(strategy, records.size(), candidates.size(), cachedCount,
                pendingList.size(), batchResult.failedBatches, outcome.getWarnings(), precheckNanos, encodeNanos,
                reuseNanos, Math.max(0, batchNanos - batchResult.checkerNanos), batchResult.checkerNanos));
        if (batchResult.allFailed) {
            outcome.fail(PipelineTaskErrorCode.EMBED_FAILED.name(), "模型调用全部失败");
            return outcome;
        }
        outcome.setSuggestedStatus(batchResult.failedBatches > 0 || !outcome.getWarnings().isEmpty()
                ? PipelineTaskStatus.PARTIAL_SUCCESS : PipelineTaskStatus.SUCCESS);
        log.info("===> EmbedPipeline 向量化完成, fileResultId={}, status={}, recordCount={},"
                        + " cachedCount={}, warningCount={}",
                context.getFileResultId(), outcome.getSuggestedStatus().name(), records.size(),
                cachedCount, outcome.getWarnings().size());
        return outcome;
    }

    // ---------------- ① 前置校验 ----------------

    private String precheck(EmbedContext context, EmbedStrategy strategy, EmbedOutcome outcome) {
        EmbeddingModel model = EmbeddingModel.of(strategy.getModel());
        if (NullUtil.isNull(model) || !model.enabled()) {
            return "模型不存在或未启用: " + strategy.getModel();
        }
        boolean registered = modelCatalogPort.listByCapability(ModelCapability.EMBEDDING).stream()
                .anyMatch(candidate -> strategy.getModel().equals(candidate.getModelKey()));
        if (!registered) {
            return "模型目录未录入或未启用: " + strategy.getModel();
        }
        if (NullUtil.isNull(strategy.getContextWindowTokens()) || strategy.getContextWindowTokens() <= 0) {
            return "模型目录缺少上下文窗口声明: " + strategy.getModel();
        }
        ChunkStrategy chunkStrategy = context.getChunkStrategy();
        if (NullUtil.isNull(chunkStrategy)) {
            // 无切片策略快照时跳过窗口校验（历史任务兼容），实测片长由四关/上游保证
            return null;
        }
        EmbedWindowRules.WindowBound window = EmbedWindowRules.resolve(
                chunkStrategy, context.getChunkProperties(), strategy, properties);
        if (!window.computable()) {
            outcome.getWarnings().add("切片兜底算法为 none，最大片长不可推算，跳过窗口兼容校验（评测专用口径）");
            return null;
        }
        if (window.bound() > window.allowed()) {
            return "切片策略最大片长 " + window.bound() + " 字符超过模型窗口 " + window.allowed()
                    + " 字符（窗口 " + strategy.getContextWindowTokens() + " tokens × "
                    + properties.getWindowCheckFactor() + "），请更换模型或调整切片策略";
        }
        return null;
    }

    // ---------------- ② 筛选 + ③ 编码 ----------------

    private List<EmbeddingRecord> encode(ChunkSet chunkSet, EmbedStrategy strategy) {
        List<EmbeddingRecord> records = new ArrayList<>();
        for (Chunk chunk : chunkSet.getChunks()) {
            EmbeddingRecord record = new EmbeddingRecord();
            record.setChunkId(chunk.getChunkId());
            record.setContentType(chunk.getContentType());
            record.setParentChunkId(chunk.getParentChunkId());
            // 父片跳过（includeParent 默认 OFF，只向量化子片）
            if (ChunkContentType.SECTION.name().equals(chunk.getContentType()) && !strategy.includeParentOn()) {
                markSkipped(records, record);
                continue;
            }
            // 编码准备：原样输入 + 哈希 + token 估算
            String inputText = template.render(strategy.getDocTemplate(), chunk);
            if (StrUtil.isBlank(inputText) && strategy.skipEmptyOn()) {
                markSkipped(records, record);
                continue;
            }
            record.setInputText(StrUtil.blankToDefault(inputText, ""));
            record.setInputTextHash(EmbedHashes.sha256Hex(inputText));
            record.setTokenCount((int) Math.ceil(inputText.length() / properties.getTokenDivisor()));
            records.add(record);
        }
        return records;
    }

    // ---------------- ④ 复用判定（账本回溯） ----------------

    /** 新→旧回溯：返回命中数；命中 record 标 CACHED + 向量本体复制 */
    private int resolveReuse(List<EmbeddingRecord> candidates, List<EmbeddingSet> history) {
        int cached = 0;
        for (EmbeddingSet previous : history) {
            List<EmbeddingRecord> misses = candidates.stream()
                    .filter(EmbedPipeline::isNotCached)
                    .toList();
            if (misses.isEmpty()) {
                // miss 清零即停
                break;
            }
            Map<String, List<Float>> vectors = indexVectors(previous);
            for (EmbeddingRecord record : misses) {
                List<Float> vector = vectors.get(record.getInputTextHash());
                if (NullUtil.isNotNull(vector)) {
                    record.setStatus(EmbedRecordStatus.CACHED.name());
                    record.setCacheHit(true);
                    record.setVector(new ArrayList<>(vector));
                    cached++;
                }
            }
        }
        return cached;
    }

    /** 历史账本 → hash→向量本体 映射（只取有本体的记录；CACHED/SUCCESS 均可用） */
    private Map<String, List<Float>> indexVectors(EmbeddingSet set) {
        Map<String, List<Float>> index = new HashMap<>();
        if (NullUtil.isNull(set) || NullUtil.isNull(set.getRecords())) {
            return index;
        }
        for (EmbeddingRecord record : set.getRecords()) {
            if (StrUtil.isNotBlank(record.getInputTextHash()) && NullUtil.isNotNull(record.getVector())
                    && !record.getVector().isEmpty()) {
                index.put(record.getInputTextHash(), record.getVector());
            }
        }
        return index;
    }

    // ---------------- ⑤ 分批模型调用 + ⑥ 四关 ----------------

    private BatchResult invokeBatches(List<EmbeddingRecord> pending, EmbedStrategy strategy, EmbedOutcome outcome) {
        BatchResult result = new BatchResult();
        if (pending.isEmpty()) {
            // 全部复用命中：零模型调用
            return result;
        }
        int batchSize = Math.max(1, ObjectUtil.defaultIfNull(strategy.getBatchSize(), properties.getBatchSize()));
        int failedBatches = 0;
        int totalBatches = 0;
        for (int from = 0; from < pending.size(); from += batchSize) {
            totalBatches++;
            List<EmbeddingRecord> batch =
                    new ArrayList<>(pending.subList(from, Math.min(from + batchSize, pending.size())));
            BatchOutcome batchOutcome = callWithRetry(batch, strategy, result);
            if (batchOutcome.consistencyRejected) {
                result.consistencyRejected = true;
                result.problems = batchOutcome.problems;
                return result;
            }
            if (!batchOutcome.success) {
                failedBatches++;
                for (EmbeddingRecord record : batch) {
                    record.setStatus(EmbedRecordStatus.FAILED.name());
                }
                outcome.getWarnings().add("批次 " + totalBatches + " 模型调用失败（重试耗尽）: "
                        + StrUtil.maxLength(String.valueOf(batchOutcome.error), 200));
            }
        }
        result.failedBatches = failedBatches;
        result.allFailed = totalBatches > 0 && failedBatches == totalBatches;
        return result;
    }

    private BatchOutcome callWithRetry(List<EmbeddingRecord> batch, EmbedStrategy strategy, BatchResult result) {
        int maxRetries = Math.max(0, ObjectUtil.defaultIfNull(strategy.getMaxRetries(), properties.getMaxRetries()));
        int timeoutMs = ObjectUtil.defaultIfNull(strategy.getTimeoutMs(), properties.getTimeoutMs());
        String requestId = IdUtil.randomUUID();
        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                sleepBackoff(attempt);
            }
            EmbeddingRequest request = new EmbeddingRequest();
            request.setModel(strategy.getModel());
            request.setTexts(batch.stream().map(EmbeddingRecord::getInputText).toList());
            request.setTimeoutMs(timeoutMs);
            request.setBatchSize(batch.size());
            request.setRequestId(requestId);
            try {
                EmbeddingResult response = embedBatch(request);
                long checkStartedNanos = System.nanoTime();
                ConsistencyResult check = checker.check(request.getTexts(), response.getEmbeddings(), strategy);
                result.checkerNanos += System.nanoTime() - checkStartedNanos;
                if (!check.passed()) {
                    if (!check.retryable()) {
                        return BatchOutcome.rejected(check.problems());
                    }
                    lastError = new IllegalStateException("四关可重试失败: " + String.join("; ", check.problems()));
                    continue;
                }
                for (int i = 0; i < batch.size(); i++) {
                    EmbeddingRecord record = batch.get(i);
                    record.setStatus(EmbedRecordStatus.SUCCESS.name());
                    record.setRequestId(requestId);
                    record.setVector(response.getEmbeddings().get(i));
                }
                return BatchOutcome.success();
            } catch (Exception e) {
                lastError = e;
                log.warn("批次模型调用失败, model={}, attempt={}/{}, requestId={}",
                        strategy.getModel(), attempt + 1, maxRetries + 1, requestId, e);
            }
        }
        return BatchOutcome.failed(lastError);
    }

    private void sleepBackoff(int attempt) {
        long backoff = properties.getRetryBackoffBaseMs() * (long) Math.pow(3, attempt - 1);
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 记录标 SKIPPED 并入列 */
    private void markSkipped(List<EmbeddingRecord> records, EmbeddingRecord record) {
        record.setStatus(EmbedRecordStatus.SKIPPED.name());
        records.add(record);
    }

    /** 记录是否未命中复用缓存 */
    private static boolean isNotCached(EmbeddingRecord record) {
        return !EmbedRecordStatus.CACHED.name().equals(record.getStatus());
    }

    // ---------------- ⑦ 组装 ----------------

    private EmbeddingSet assemble(EmbedContext context, EmbedStrategy strategy, List<EmbeddingRecord> records,
                                  int cachedCount) {
        ChunkSet chunkSet = context.getChunkSet();
        EmbeddingSet set = new EmbeddingSet();
        set.setEmbeddingSetId("es-" + chunkSet.getChunkSetId() + "-" + strategy.fullVersion());
        set.setFileResultId(context.getFileResultId());
        set.setChunkSetRef(context.getChunkSetRef());
        set.setChunkSetId(chunkSet.getChunkSetId());
        set.setStrategyVersion(strategy.fullVersion());
        set.setModel(strategy.getModel());
        set.setDimension(ObjectUtil.defaultIfNull(strategy.getDimension(), 0));
        set.setMetric(strategy.getMetric());
        set.setNormalized(Boolean.TRUE.equals(strategy.getNormalized()));
        set.setRecordCount(records.size());
        set.setCachedCount(cachedCount);
        int index = 1;
        for (EmbeddingRecord record : records) {
            record.setEmbeddingId(String.format("emb-%04d", index++));
        }
        set.setRecords(records);
        return set;
    }

    private List<StepLogInfo> buildStepLogs(EmbedStrategy strategy, int total, int candidates, int cached,
                                            int pending, int failedBatches, List<String> warnings,
                                            long precheckNanos, long encodeNanos, long reuseNanos,
                                            long gatewayNanos, long checkerNanos) {
        List<StepLogInfo> logs = new ArrayList<>();
        logs.add(StepLogHelper.build("前置校验", "window-compat-v1", total, 0, warnings.size(), precheckNanos / 1_000_000));
        logs.add(StepLogHelper.build("筛选与编码", "identity-encode-v1", candidates, 0, 0, encodeNanos / 1_000_000));
        logs.add(StepLogHelper.build("复用判定", "ledger-reuse-v1", cached, 0, 0, reuseNanos / 1_000_000));
        logs.add(StepLogHelper.build("模型调用", strategy.getModel(), pending - failedBatches, 0,
                failedBatches, gatewayNanos / 1_000_000));
        logs.add(StepLogHelper.build("四关校验", "consistency-v1", pending, 0, 0, checkerNanos / 1_000_000));
        return logs;
    }

    /** 批次结果聚合 */
    private static final class BatchResult {
        private boolean consistencyRejected;
        private List<String> problems = List.of();
        private int failedBatches;
        private boolean allFailed;
        private long checkerNanos;
    }

    /** 单批结果 */
    private static final class BatchOutcome {
        private final boolean success;
        private final boolean consistencyRejected;
        private final List<String> problems;
        private final Exception error;

        private BatchOutcome(boolean success, boolean consistencyRejected, List<String> problems, Exception error) {
            this.success = success;
            this.consistencyRejected = consistencyRejected;
            this.problems = problems;
            this.error = error;
        }

        static BatchOutcome success() {
            return new BatchOutcome(true, false, List.of(), null);
        }

        static BatchOutcome rejected(List<String> problems) {
            return new BatchOutcome(false, true, problems, null);
        }

        static BatchOutcome failed(Exception error) {
            return new BatchOutcome(false, false, List.of(), error);
        }
    }
}
