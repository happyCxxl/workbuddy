package com.workbuddy.common.enums.task;

import lombok.Getter;

import java.util.Set;

/**
 * 处理链环节：一次环节触发 = 一条 wb_knowledge_pipeline_task。
 * 枚举常量名是落库、血缘与接口传参用的英文码（{@link #name()}）；中文标签只供展示与日志（{@link #getLabel()}）。
 *
 * @author cxxl
 */
@Getter
public enum PipelineStage {

    /** 解析 */
    PARSE("解析"),

    /** 统一结构组装 */
    STRUCTURE("组装"),

    /** 预处理 */
    PREPROCESS("预处理"),

    /** 切片 */
    CHUNK("切片"),

    /** 向量化 */
    EMBED("向量化"),

    /** 索引构建 */
    BUILD_INDEX("索引构建");

    /** 环节中文标签（展示与日志用；落库、血缘与接口传参一律用 {@link #name()}） */
    private final String label;

    PipelineStage(String label) {
        this.label = label;
    }

    /** 逐文件执行链五环节（解析 → 组装 → 预处理 → 切片 → 向量化，不含库级 BUILD_INDEX）：
     * 血缘执行树与产物内容接口的白名单同一口径，集中在这里，避免各处各写一份。
     */
    public static final Set<String> FILE_CHAIN_STAGES = Set.of(
            PARSE.name(), STRUCTURE.name(), PREPROCESS.name(), CHUNK.name(), EMBED.name());
}
