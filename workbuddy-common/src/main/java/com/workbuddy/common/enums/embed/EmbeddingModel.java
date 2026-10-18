package com.workbuddy.common.enums.embed;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量模型目录（静态注册）：模型名 · 维度 · 度量 · 归一化 · 上下文窗口 tokens · 单批上限 · 启用。
 * 单一事实源：前置校验（窗口兼容）/ 四关（维度/度量）/ 保存校验（存在且 enabled）/ 前端展示均以此为准。
 * 当前供应商 = 阿里云 DashScope（模型名即 DashScope 渠道名）。
 *
 * <p>数值为占位/厂商口径值，联调实测后只改本枚举常量即可。</p>
 */
public enum EmbeddingModel {

    /** 阿里云 text-embedding-v4：通用文本向量（1024 维） */
    TEXT_EMBEDDING_V4("text-embedding-v4",
            1024, EmbedMetric.COSINE, true,
            8192, 64, true,
            "阿里云 text-embedding-v4（默认启用）：1024 维；上下文窗口 8192 tokens；单批上限 64"),

    /** 阿里云 text-embedding-v3：通用文本向量（1024 维，备选） */
    TEXT_EMBEDDING_V3("text-embedding-v3",
            1024, EmbedMetric.COSINE, true,
            8192, 64, false,
            "阿里云 text-embedding-v3（备选）：1024 维；上下文窗口 8192 tokens；单批上限 64"),

    /** 阿里云 text-embedding-v2：通用文本向量（1536 维，旧版备选） */
    TEXT_EMBEDDING_V2("text-embedding-v2",
            1536, EmbedMetric.COSINE, true,
            2048, 64, false,
            "阿里云 text-embedding-v2（旧版备选）：1536 维；上下文窗口 2048 tokens；单批上限 64");

    /** 模型名（DashScope 渠道口径） */
    private final String key;

    /** 向量维度 */
    private final int dimension;

    /** 度量 */
    private final EmbedMetric metric;

    /** 是否归一化（COSINE 度量必须归一化） */
    private final boolean normalized;

    /** 上下文窗口（tokens）；取值待核对模型文档 */
    private final int contextWindowTokens;

    /** 单批上限（条）；取值待核对模型文档 */
    private final int batchLimit;

    /** 是否启用（目录校验：未启用模型拒绝保存/触发） */
    private final boolean enabled;

    /** 说明（人类可读） */
    private final String desc;

    EmbeddingModel(String key, int dimension, EmbedMetric metric, boolean normalized,
                   int contextWindowTokens, int batchLimit, boolean enabled, String desc) {
        this.key = key;
        this.dimension = dimension;
        this.metric = metric;
        this.normalized = normalized;
        this.contextWindowTokens = contextWindowTokens;
        this.batchLimit = batchLimit;
        this.enabled = enabled;
        this.desc = desc;
    }

    /** 模型名（网关口径）。 */
    public String key() {
        return key;
    }

    /** 向量维度。 */
    public int dimension() {
        return dimension;
    }

    /** 度量。 */
    public EmbedMetric metric() {
        return metric;
    }

    /** 是否归一化。 */
    public boolean normalized() {
        return normalized;
    }

    /** 上下文窗口（tokens）。 */
    public int contextWindowTokens() {
        return contextWindowTokens;
    }

    /** 单批上限（条）。 */
    public int batchLimit() {
        return batchLimit;
    }

    /** 是否启用。 */
    public boolean enabled() {
        return enabled;
    }

    /** 说明（人类可读）。 */
    public String desc() {
        return desc;
    }

    /** 按模型名精确查找；未识别返回 null。 */
    public static EmbeddingModel of(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        for (EmbeddingModel model : values()) {
            if (model.key.equals(key)) {
                return model;
            }
        }
        return null;
    }

    /** 启用中模型列表（前端下拉 / 默认模型选择）。 */
    public static List<EmbeddingModel> enabledModels() {
        List<EmbeddingModel> enabled = new ArrayList<>();
        for (EmbeddingModel model : values()) {
            if (model.enabled) {
                enabled.add(model);
            }
        }
        return enabled;
    }

    /** 首个启用模型（内置默认策略用；目录恒至少一个启用模型）。 */
    public static EmbeddingModel firstEnabled() {
        return enabledModels().getFirst();
    }
}
