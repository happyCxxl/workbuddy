package com.workbuddy.worker.structure.title;

import com.workbuddy.common.domain.structure.TitleEvidence;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 标题判定结果（规则链内部载体）：
 * title（命中 + 层级 + 证据）/ candidate（编号模式命中但缺字号佐证，只告警）/ paragraph（非标题）。
 */
@Getter
@RequiredArgsConstructor
public final class TitleDecision {

    private final boolean title;

    private final int level;

    private final TitleEvidence evidence;

    private final boolean candidate;

    /** 命中工厂：title + 层级 + 证据 */
    public static TitleDecision title(int level, TitleEvidence evidence) {
        return new TitleDecision(true, level, evidence, false);
    }

    /** 候选工厂：编号模式命中但缺字号佐证（只计入候选告警，不升级） */
    public static TitleDecision candidate() {
        return new TitleDecision(false, 0, null, true);
    }

    /** 非标题工厂 */
    public static TitleDecision paragraph() {
        return new TitleDecision(false, 0, null, false);
    }

    /** 证据工厂：级联名 + 字号 + 加粗 + 模式串 */
    public static TitleEvidence evidence(String cascade, Double size, boolean bold, String pattern) {
        TitleEvidence evidence = new TitleEvidence();
        evidence.setCascade(cascade);
        evidence.setFontSize(size);
        evidence.setBold(bold);
        evidence.setPattern(pattern);
        return evidence;
    }
}
