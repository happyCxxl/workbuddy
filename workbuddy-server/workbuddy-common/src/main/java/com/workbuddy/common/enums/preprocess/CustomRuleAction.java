package com.workbuddy.common.enums.preprocess;

import cn.hutool.core.util.StrUtil;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 自定义规则动作目录：用户自定义正则规则的三种动作，带说明与替换文本约束。
 * 保存校验的取值清单与替换文本约束、以及前端动作下拉的说明文案均以此为准。
 */
public enum CustomRuleAction {

    /** 命中整体移除 */
    REMOVE("命中内容整体移除（替换为空）"),

    /** 命中部分替换（replacement 支持 $1 组引用，必填） */
    REPLACE("命中内容按替换文本改写（支持 $1 组引用，替换文本必填）"),

    /** 仅保留命中内容（替换原文本） */
    EXTRACT("只保留命中内容（多个命中以空格连接；无命中保持原文）");

    private final String desc;

    CustomRuleAction(String desc) {
        this.desc = desc;
    }

    /**
     * 动作说明（人类可读）。
     *
     * @return 说明文案
     */
    public String desc() {
        return desc;
    }

    /**
     * 是否需要替换文本：只有 REPLACE 需要；其余动作携带替换文本按非法处理。
     *
     * @return 需要替换文本为 true
     */
    public boolean needsReplacement() {
        return this == REPLACE;
    }

    /**
     * 动作名清单（校验提示与前端下拉同源，形如 REMOVE/REPLACE/EXTRACT）。
     *
     * @return 以斜杠分隔的动作名
     */
    public static String names() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining("/"));
    }

    /**
     * 按名称解析（大小写不敏感）。
     *
     * @param name 动作名
     * @return 命中的动作；未识别时为 null
     */
    public static CustomRuleAction of(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        for (CustomRuleAction action : values()) {
            if (action.name().equalsIgnoreCase(name)) {
                return action;
            }
        }
        return null;
    }
}
