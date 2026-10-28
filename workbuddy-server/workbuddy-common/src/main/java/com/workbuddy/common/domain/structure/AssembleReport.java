package com.workbuddy.common.domain.structure;

import lombok.Data;

import java.util.Map;

/**
 * 组装报告：多工艺统计 + 溯源可回溯占比。
 * 内部流转：不落产物、不落子步骤记录（详情页统计由 biz 侧按产物现算，本类只服务管线内判定与日志）。
 */
@Data
public class AssembleReport {

    /** 计入溯源分母的元素数（接续合并后、排除结构性节点） */
    private int provenanceScopeCount;

    /** 去重合并对数 */
    private int mergePairs;

    /** 冲突数 */
    private int conflictCount;

    /** 阅读顺序切分数（预留未实现：从未赋值，接入顺序切分统计时启用） */
    private int orderCuts;

    /** 标题推定数 */
    private int titleCount;

    /** 标题推定数（按级联层统计，如 style:1, number-pattern:3） */
    private Map<String, Integer> titleCountByCascade;

    /** 标题候选告警数 */
    private int titleCandidateCount;

    /** 跨页接续表数 */
    private int continuationCount;

    /** 溯源可回溯占比（0~1） */
    private double traceableRatio;

    /** 重复页数（组装环节识别，除首份外） */
    private int repeatPageCount;

    /** 重复段数（组装环节识别，除首份外） */
    private int repeatSegmentCount;

    /** 噪声页数（组装环节识别） */
    private int noisePageCount;
}
