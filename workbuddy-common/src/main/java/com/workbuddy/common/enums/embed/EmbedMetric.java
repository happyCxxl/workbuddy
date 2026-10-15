package com.workbuddy.common.enums.embed;

import cn.hutool.core.util.StrUtil;

/**
 * 向量度量（模型目录带出，与 Milvus metric 对齐）：
 * COSINE（余弦，向量需归一化）/ IP（内积）/ L2（欧氏距离）。
 * 四关校验与建索引共用本口径。
 *
 * @author cxxl
 */
public enum EmbedMetric {

    /** 余弦相似度（向量需归一化） */
    COSINE("COSINE", "余弦相似度（向量需归一化）"),

    /** 内积 */
    IP("IP", "内积"),

    /** 欧氏距离 */
    L2("L2", "欧氏距离");

    /** 序列化键（JSON 契约 / Milvus metric 值） */
    private final String key;

    /** 说明（人类可读） */
    private final String desc;

    EmbedMetric(String key, String desc) {
        this.key = key;
        this.desc = desc;
    }

    /** 序列化键 */
    public String key() {
        return key;
    }

    /** 说明（人类可读） */
    public String desc() {
        return desc;
    }

    /** 按键精确查找；未识别返回 null */
    public static EmbedMetric of(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        for (EmbedMetric metric : values()) {
            if (metric.key.equalsIgnoreCase(key)) {
                return metric;
            }
        }
        return null;
    }
}
