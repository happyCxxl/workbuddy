package com.workbuddy.worker.chunking.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.chunk.ChunkKind;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.pipeline.StepLogHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片产物与步骤日志支撑：ChunkSet 头组装、标题路径回填、六步子步骤日志与 capability 推导。
 * 单次运行统计随 {@link Stats} 实例传入，本类自身无状态。
 */
public final class ChunkMetadataSupport {

    private ChunkMetadataSupport() {
    }

    /**
     * 组装切片集合头（集合 ID 由文档 ID 与策略完整版本推导）。
     *
     * @param context  切片上下文
     * @param strategy 切片策略快照
     * @param chunks   已编号切片
     * @return 切片集合
     */
    public static ChunkSet assembleChunkSet(ChunkContext context, ChunkStrategy strategy, List<Chunk> chunks) {
        ChunkSet chunkSet = new ChunkSet();
        chunkSet.setChunkSetId("cs-" + context.getView().getDocumentId() + "-" + strategy.fullVersion());
        chunkSet.setFileResultId(context.getFileResultId());
        chunkSet.setDocumentId(context.getView().getDocumentId());
        chunkSet.setStrategyVersion(strategy.fullVersion());
        chunkSet.setUpstreamProductRef(context.getUpstreamProductRef());
        chunkSet.setChunkCount(chunks.size());
        chunkSet.setChunks(chunks);
        return chunkSet;
    }

    /**
     * 回填切片标题路径：仅空标题路径的片按当前章节路径拼接，已有路径不覆盖。
     *
     * @param chunks  切片列表
     * @param context 切片上下文（当前章节路径）
     */
    public static void fillTitlePath(List<Chunk> chunks, SliceContext context) {
        for (Chunk chunk : chunks) {
            if (StrUtil.isBlank(chunk.getTitlePath())) {
                chunk.setTitlePath(String.join(" > ", context.getTitlePath()));
            }
        }
    }

    /**
     * 组装六步子步骤日志：内容路由、正文/表格/图片/兜底切片、父子关系补充。
     *
     * @param stats    运行统计
     * @param strategy 切片策略快照（capability 来源）
     * @return 子步骤日志
     */
    public static List<StepLogInfo> buildStepLogs(Stats stats, ChunkStrategy strategy) {
        List<StepLogInfo> logs = new ArrayList<>();
        logs.add(step("内容路由", "content-router-v1", stats.routeMatched, 0, 0, stats.routeNanos));
        logs.add(step("正文切片", capability(strategy.routeAlgorithm(ChunkRoute.BODY)),
                stats.bodyMatched, stats.bodyLen, stats.bodyWarnings, stats.bodyNanos));
        logs.add(step("表格切片", capability(strategy.routeAlgorithm(ChunkRoute.TABLE)),
                stats.tableMatched, stats.tableLen, stats.tableWarnings, stats.tableNanos));
        logs.add(step("图片切片", capability(strategy.routeAlgorithm(ChunkRoute.IMAGE)),
                stats.imageMatched, stats.imageLen, stats.imageWarnings, stats.imageNanos));
        // 兜底耗时恒 0：兜底在正文/表格切片器内部触发，不可单独计时（统计仍按 contentType=FALLBACK 计数）
        logs.add(step("兜底切片", capability(strategy.routeAlgorithm(ChunkRoute.FALLBACK)),
                stats.fallbackMatched, stats.fallbackLen, 0, 0));
        logs.add(step("父子关系补充", "parent-child-attach-v1", stats.parentMatched, stats.parentLen, 0,
                stats.parentNanos));
        return logs;
    }

    /** 子步骤 capability：由所选算法的枚举键推导（不带版本号；未识别回退 unknown） */
    static String capability(ChunkAlgorithm algorithm) {
        return NullUtil.isNull(algorithm) ? "unknown" : algorithm.key();
    }

    private static StepLogInfo step(String stepName, String capability, int matched, int totalLen, int warnings,
                                    long nanos) {
        return StepLogHelper.build(stepName, capability, matched, totalLen, warnings, nanos / 1_000_000);
    }

    /** 子步骤统计（产片数/总长/告警/各阶段耗时纳秒，平均长度=总长÷片数） */
    static final class Stats {
        int routeMatched;
        int bodyMatched;
        int bodyLen;
        int bodyWarnings;
        int tableMatched;
        int tableLen;
        int tableWarnings;
        int imageMatched;
        int imageLen;
        int imageWarnings;
        int fallbackMatched;
        int fallbackLen;
        int parentMatched;
        int parentLen;
        long routeNanos;
        long bodyNanos;
        long tableNanos;
        long imageNanos;
        long parentNanos;

        void addNanos(ChunkKind kind, long nanos) {
            switch (kind) {
                case BODY -> bodyNanos += nanos;
                case TABLE -> tableNanos += nanos;
                case IMAGE -> imageNanos += nanos;
                default -> {
                }
            }
        }

        void incr(ChunkKind kind, List<Chunk> chunks) {
            switch (kind) {
                case BODY -> {
                    for (Chunk chunk : chunks) {
                        if (ChunkContentType.FALLBACK.name().equals(chunk.getContentType())) {
                            fallbackMatched++;
                            fallbackLen += chunk.getCharCount();
                        } else {
                            bodyMatched++;
                            bodyLen += chunk.getCharCount();
                        }
                    }
                }
                case TABLE -> {
                    tableMatched += chunks.size();
                    tableLen += chunks.stream().mapToInt(Chunk::getCharCount).sum();
                }
                case IMAGE -> {
                    imageMatched += chunks.size();
                    imageLen += chunks.stream().mapToInt(Chunk::getCharCount).sum();
                }
                default -> {
                }
            }
        }

        void warn(ChunkKind kind) {
            switch (kind) {
                case BODY -> bodyWarnings++;
                case TABLE -> tableWarnings++;
                case IMAGE -> imageWarnings++;
                default -> {
                }
            }
        }
    }
}
