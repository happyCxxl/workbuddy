package com.workbuddy.worker.structure.impl.title;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 中文括号序号标题规则：（一）…（三级；缺字号佐证只计候选）。
 *
 * @author cxxl
 */
@Component
public class CnParenTitleRule extends AbstractCnNumberTitleRule {

    /** 中文序号：（一） */
    private static final Pattern CN_PAREN_PATTERN = Pattern.compile("^（[一二三四五六七八九十]+）.*");

    public CnParenTitleRule() {
        super(40, CN_PAREN_PATTERN, "（一）");
    }
}
