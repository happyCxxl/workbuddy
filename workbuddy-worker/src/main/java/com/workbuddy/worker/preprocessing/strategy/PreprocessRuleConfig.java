package com.workbuddy.worker.preprocessing.strategy;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 预处理规则配置：每条规则的处置方式/开关 + 参数。
 * 三态规则用 action（KEEP/MARK/EXCLUDE），开关规则用 enabled（ON/OFF）；
 * 重复（repeat）历史上只有 enabled 开关，迁移期仍接受只给 enabled 的旧配置（ON=EXCLUDE / OFF=KEEP），新配置一律写 action；
 * 参数数值均以字符串存（解析时由 {@code PreprocessAlgorithmSpec} 补默认值）。
 *
 * @author cxxl
 */
@Data
public class PreprocessRuleConfig {

    /** 处置方式（三态规则）：KEEP/MARK/EXCLUDE */
    private String action;

    /** 开关（开关规则）：ON/OFF */
    private String enabled;

    /** 规则参数（键见 PreprocessParam） */
    private Map<String, String> params = new HashMap<>();
}
