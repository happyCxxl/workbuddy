package com.workbuddy.worker.preprocessing.strategy;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义规则组：总开关 + 有序规则列表（自上而下顺序执行）。
 *
 * @author cxxl
 */
@Data
public class CustomRuleGroup {

    public CustomRuleGroup() {
        this.enabled = PreprocessStrategy.ON;
    }

    /** 总开关：ON/OFF */
    private String enabled;

    /** 有序规则列表（≤20 条，保存校验强制） */
    private List<PreprocessCustomRule> rules = new ArrayList<>();
}
