package com.workbuddy.worker.pipeline;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.StageStrategy;

import java.util.function.UnaryOperator;

/**
 * 策略快照解析共用骨架：快照 JSON → 策略对象。
 * 空快照、非法 JSON、反序列化为 null 与补全失败一律抛 {@link IllegalStateException}
 * （数据问题按异常处置，不做任何内置默认策略兜底）；缺省参数的补全由各环节的 normalizer 负责。
 *
 * @author cxxl
 */
public final class StrategySnapshotSupport {

    /** 异常消息尾部保留的快照长度（排查用） */
    private static final int SNAPSHOT_KEEP = 200;

    private StrategySnapshotSupport() {
    }

    /**
     * 快照 JSON → 策略对象：空快照、非法 JSON、反序列化为 null 与补全失败一律抛异常。
     *
     * @param snapshot   策略快照 JSON 文本（任务行或策略版本行），可空
     * @param type       策略类型（反序列化目标）
     * @param stageLabel 环节中文名（异常文案前缀）
     * @param normalize  缺省参数补全（各环节的 AlgorithmSpec.normalize）
     * @param <T>        策略类型
     * @return 补全后的策略对象（非空）
     * @throws IllegalStateException 快照为空或损坏（消息带截断后的快照）
     */
    public static <T> T parse(String snapshot, Class<T> type, String stageLabel, UnaryOperator<T> normalize) {
        try {
            T strategy = StrUtil.isBlank(snapshot) ? null : JsonUtil.toObject(snapshot, type);
            ThrowUtil.throwIf(NullUtil.isNull(strategy), () -> corrupt(stageLabel, snapshot, null));
            return normalize.apply(strategy);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw corrupt(stageLabel, snapshot, e);
        }
    }

    /**
     * 清掉配置快照里没有的元信息：配置 JSON 只有参数，name/version 由策略版本行回填。
     *
     * @param strategy 解析后的策略对象
     * @param <T>      策略类型
     * @return 传入的策略对象（name/version 已置空）
     */
    public static <T extends StageStrategy> T clearMeta(T strategy) {
        strategy.setName(null);
        strategy.setVersion(null);
        return strategy;
    }

    /** 快照为空/损坏的异常：消息带截断后的快照（排查用） */
    public static IllegalStateException corrupt(String stageLabel, String snapshot, Throwable cause) {
        return new IllegalStateException(stageLabel + "策略快照为空或已损坏，无法解析, snapshot="
                + StrUtil.maxLength(String.valueOf(snapshot), SNAPSHOT_KEEP), cause);
    }
}
