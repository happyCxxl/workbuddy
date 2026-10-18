package com.workbuddy.worker.structure.impl.title;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 中文顿号序号标题规则：一、…（三级；缺字号佐证只计候选）。
 */
@Component
public class CnDotTitleRule extends AbstractCnNumberTitleRule {

    /** 中文序号：一、 */
    private static final Pattern CN_DOT_PATTERN = Pattern.compile("^[一二三四五六七八九十]+、.*");

    public CnDotTitleRule() {
        super(50, CN_DOT_PATTERN, "一、");
    }
}
