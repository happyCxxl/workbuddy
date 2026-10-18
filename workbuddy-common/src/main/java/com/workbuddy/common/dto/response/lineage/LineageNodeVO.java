package com.workbuddy.common.dto.response.lineage;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 执行树节点 = 一次运行（节点是运行不是策略，同策略多跑 = 多个节点）。
 */
@Data
public class LineageNodeVO {

    /** 任务 ID（雪花转字符串） */
    private Long taskId;

    /** 环节（PipelineStage 枚举名） */
    private String stage;

    /** 任务状态 */
    private String status;

    /** 错误码 / 错误信息（失败时非空） */
    private String errorCode;

    private String errorMsg;

    /** 策略版本（PREPROCESS/CHUNK/EMBED 有，name-version 串；PARSE/STRUCTURE 为空） */
    private String strategyVersion;

    /**
     * 能力快照（无策略环节展示用：PARSE/STRUCTURE 有，其余环节为空）。
     *
     * <p>由服务层把 {@code product.capabilitySnapshot} 的 JSON 文本解析成
     * {@link LineageCapabilityVO} 后返回；JSON 无法解析时本字段为 null（不回落原文，
     * 避免把脏数据继续往上层传）。
     */
    private LineageCapabilityVO capability;

    /** 产物 ID（成功任务对应产物；无产物为空）。触发下游环节时传此值（非任务 ID） */
    private Long productId;

    /** 产物引用（成功有产物时非空） */
    private String artifactId;

    /** 产物内容指纹 */
    private String contentHash;

    /**
     * 环节统计（展示用，键名各环节自定义）：CHUNK=chunkCount；EMBED=recordCount/cachedCount；
     * PREPROCESS=matched/changed（step_log 聚合）；STRUCTURE=元素/标题/章节层数/层级分布/疑似续表/溯源覆盖率/耗时。
     *
     * <p>值是 {@code Object}：计数按数字下发，前端按数字格式化千分位，不在这里转成字符串。
     * 解析环节的统计结构固定，走 {@link #parseStats}，不重复放这里。
     */
    private Map<String, Object> stats;

    /**
     * 环节摘要行文案：组装=「N 元素 · M 标题 · 疑似续表 X 处 · 溯源覆盖率 Y%」，空树给原因，失败取失败原因。
     *
     * <p>非本环节、进行中、以及**产物不可读导致统计缺失**时均为空 —— 统计没到手就不陈述结论。
     */
    private String stageSummary;

    /**
     * 解析环节运行统计（元素构成/问题单元/耗时）：仅 PARSE 且该次运行产出产物时非空。
     *
     * <p>页面按单元号表达问题页范围，故直接下发始末单元号而不是区间列表。
     */
    private LineageParseStatsVO parseStats;

    /**
     * 解析环节摘要行文案：成功=无异常、部分成功=问题单元数与范围、失败=失败原因。
     *
     * <p>非解析环节、解析进行中、以及**产物不可读导致统计缺失**时均为空 —— 统计没到手就不陈述结论。
     */
    private String parseSummary;

    /** 任务时间 */
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
