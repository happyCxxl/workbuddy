package com.workbuddy.worker.embedding;

import com.workbuddy.common.enums.embed.EmbedSwitch;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量化参数配置（knowledge.embed 前缀；application.yml 同名 kebab-case 键）。
 * 值源 = EmbedStrategy 参数缺省（策略快照未显式配置时补默认）。
 *
 * @author cxxl
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.embed")
public class EmbedProperties {

    /** 批量大小默认（向量化侧默认 32） */
    private int batchSize = 32;

    /** 单批超时默认（毫秒；策略未指定时取本值） */
    private int timeoutMs = 30000;

    /** 最大重试默认（指数退避 1s/3s/9s…，失败批次隔离） */
    private int maxRetries = 2;

    /** 复用开关默认（ON=启用账本复用） */
    private EmbedSwitch cacheEnabled = EmbedSwitch.ON;

    /** 父片向量化默认（OFF=只向量化子片） */
    private EmbedSwitch includeParent = EmbedSwitch.OFF;

    /** 空文本跳过默认 */
    private EmbedSwitch skipEmpty = EmbedSwitch.ON;

    /** 账本复用回溯上限（新→旧最多回看多少个同策略账本） */
    private int reuseBacktrackLimit = 10;

    /** Token 估算除数（字符数 ÷ 1.5，中文经验值） */
    private double tokenDivisor = 1.5;

    /** 前置校验窗口系数：切片最大片长 ≤ 模型窗口 tokens × 本系数 */
    private double windowCheckFactor = 1.5;

    /** 批次重试退避基数（毫秒；第 n 次重试等待 base × 3^(n-1)） */
    private int retryBackoffBaseMs = 1000;
}
