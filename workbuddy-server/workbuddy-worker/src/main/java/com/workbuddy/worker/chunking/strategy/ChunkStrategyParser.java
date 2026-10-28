package com.workbuddy.worker.chunking.strategy;

import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.pipeline.StrategySnapshotSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 切片策略解析器：快照 JSON → ChunkStrategy（补全缺省路由/默认参数/流程层默认）。
 * 触发（runner 读任务快照）与保存侧（控制面 toStrategy）共用同一解析口径；
 * 快照为空或损坏一律抛异常，由上层按数据问题处置，本类不提供任何内置默认策略。
 * 旧扁平格式（options 单键）不再支持。
 */
@Component
@RequiredArgsConstructor
public class ChunkStrategyParser {

    private final ChunkProperties properties;

    /**
     * 快照解析：空快照、JSON 非法、反序列化为 null 与后续补全失败一律抛 IllegalStateException；
     * 成功则补全缺省路由/默认参数/流程层默认后返回。
     *
     * @param snapshot 策略快照 JSON 文本（任务行或策略版本行），可空
     * @return 补全后的切片策略（非空）
     * @throws IllegalStateException 快照为空或损坏（消息带截断后的快照）
     */
    public ChunkStrategy parse(String snapshot) {
        return StrategySnapshotSupport.parse(snapshot, ChunkStrategy.class, "切片",
                strategy -> ChunkAlgorithmSpec.normalize(strategy, properties));
    }

    /**
     * 配置 JSON（仅 routes/pipeline，无 name/version）→ 补全默认后的策略对象（name/version 由回填补全）。
     * 快照为空或损坏与 {@link #parse(String)} 同样抛异常。
     *
     * @param configSnapshot 策略版本行上的配置快照 JSON 文本，可空
     * @return 补全后的切片策略（name/version 为空，非空）
     * @throws IllegalStateException 快照为空或损坏（消息带截断后的快照）
     */
    public ChunkStrategy parseConfig(String configSnapshot) {
        return StrategySnapshotSupport.clearMeta(parse(configSnapshot));
    }
}
