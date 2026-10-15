package com.workbuddy.worker.preprocessing.strategy;

import lombok.Data;

/**
 * 用户自定义正则规则：pattern + 动作；REPLACE 携带 replacement（支持 $1 组引用）。
 * 保存校验（biz 40001）保证 pattern 合法、上限与动作约束。
 *
 * @author cxxl
 */
@Data
public class PreprocessCustomRule {

    /** 正则表达式（Java 语法，≤200 字符） */
    private String pattern;

    /** 动作：REMOVE / REPLACE / EXTRACT */
    private String action;

    /** 替换文本（仅 REPLACE 使用，支持组引用） */
    private String replacement;
}
