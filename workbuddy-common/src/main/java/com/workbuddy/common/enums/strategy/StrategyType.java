package com.workbuddy.common.enums.strategy;

import cn.hutool.core.util.StrUtil;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 策略类型（wb_knowledge_pipeline_strategy_version.type）。
 *
 * <p>四类策略的**码值**集中在这里。
 *
 * <p>与 {@link com.workbuddy.common.enums.task.PipelineStage} 的关系：环节枚举含
 * PARSE / STRUCTURE（不挂策略）与 BUILD_INDEX（索引构建），不能直接用环节枚举当策略类型。
 *
 * <p>**绑定可分性**（{@link #bindable()}）：只有三件套能绑到知识库；检索规则不绑 KB ——
 * 它走索引版本行的 `default_rule_id`。
 */
public enum StrategyType {

    /** 数据预处理 */
    PREPROCESS("PREPROCESS", true),

    /** 切片 */
    CHUNK("CHUNK", true),

    /** 向量化 */
    EMBED("EMBED", true),

    /** 检索（不绑知识库：走索引版本的默认检索规则） */
    RETRIEVAL("RETRIEVAL", false);

    /** 码值（与库表 type 列、接口入参一致） */
    private final String key;

    /** 是否可绑定到知识库 */
    private final boolean bindable;

    StrategyType(String key, boolean bindable) {
        this.key = key;
        this.bindable = bindable;
    }

    /**
     * 码值。
     *
     * @return 策略类型码值
     */
    public String key() {
        return key;
    }

    /**
     * 是否可绑定到知识库（三件套为 true，检索为 false）。
     *
     * @return 可绑定为 true
     */
    public boolean bindable() {
        return bindable;
    }

    /**
     * 全部策略类型码值。
     *
     * @return 码值集合
     */
    public static Set<String> keys() {
        return Arrays.stream(values()).map(StrategyType::key).collect(Collectors.toSet());
    }

    /**
     * 可绑定到知识库的类型码值（预处理 / 切片 / 向量化）。
     *
     * @return 可绑定类型的码值集合
     */
    public static Set<String> bindableKeys() {
        return Arrays.stream(values()).filter(StrategyType::bindable)
                .map(StrategyType::key).collect(Collectors.toSet());
    }

    /**
     * 按码值查找。
     *
     * @param key 码值（忽略大小写）
     * @return 命中的类型；未识别时为 null
     */
    public static StrategyType of(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        for (StrategyType type : values()) {
            if (type.key.equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }
}
