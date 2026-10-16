package com.workbuddy.worker.embedding.strategy;

import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.pipeline.StrategySnapshotSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 向量化策略解析器：快照 JSON → EmbedStrategy（补默认）。
 * 触发（runner 读任务快照）与保存侧（控制面 toStrategy）共用同一解析口径；
 * 快照为空或损坏一律抛异常，由上层按数据问题处置，本类不提供任何内置默认策略。
 *
 * @author cxxl
 */
@Component
@RequiredArgsConstructor
public class EmbedStrategyParser {

    private final EmbedProperties properties;

    /**
     * 快照解析：空快照、JSON 非法、反序列化为 null 与后续补全失败一律抛 IllegalStateException；
     * 成功则补默认后返回。
     *
     * @param snapshot 策略快照 JSON 文本（任务行或策略版本行），可空
     * @return 补全后的向量化策略（非空）
     * @throws IllegalStateException 快照为空或损坏（消息带截断后的快照）
     */
    public EmbedStrategy parse(String snapshot) {
        return StrategySnapshotSupport.parse(snapshot, EmbedStrategy.class, "向量化",
                strategy -> EmbedAlgorithmSpec.normalize(strategy, properties));
    }

    /**
     * 配置 JSON（无 name/version）→ 补全默认后的策略对象（name/version 由回填补全）。
     * 快照为空或损坏与 {@link #parse(String)} 同样抛异常。
     *
     * @param configSnapshot 策略版本行上的配置快照 JSON 文本，可空
     * @return 补全后的向量化策略（name/version 为空，非空）
     * @throws IllegalStateException 快照为空或损坏（消息带截断后的快照）
     */
    public EmbedStrategy parseConfig(String configSnapshot) {
        return StrategySnapshotSupport.clearMeta(parse(configSnapshot));
    }
}
