package com.workbuddy.worker.embedding;

import com.workbuddy.common.enums.embed.EmbedSwitch;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量化参数配置（knowledge.embed 前缀；同名 kebab-case 键在 config/knowledge/knowledge-embed.yml）。
 * 字段不带代码侧默认值，配置面是唯一基线；值源 = EmbedStrategy 参数缺省（策略快照未显式配置时取本配置）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.embed")
public class EmbedProperties {

    /** 单批条数（策略快照未显式指定时取本配置） */
    private int batchSize;

    /** 单批超时默认（毫秒；策略未指定时取本值） */
    private int timeoutMs;

    /** 最大重试次数（指数退避，失败批次隔离） */
    private int maxRetries;

    /** 复用开关默认（ON=启用账本复用） */
    private EmbedSwitch cacheEnabled;

    /** 父片向量化默认（OFF=只向量化子片） */
    private EmbedSwitch includeParent;

    /** 空文本跳过默认 */
    private EmbedSwitch skipEmpty;

    /** 账本复用回溯上限（新→旧最多回看多少个同策略账本） */
    private int reuseBacktrackLimit;

    /** Token 估算除数（字符数 ÷ 该值，中文经验值） */
    private double tokenDivisor;

    /** 前置校验窗口系数：切片最大片长 ≤ 模型窗口 tokens × 本系数 */
    private double windowCheckFactor;

    /** 批次重试退避基数（毫秒；第 n 次重试等待 base × 3^(n-1)） */
    private int retryBackoffBaseMs;
}
