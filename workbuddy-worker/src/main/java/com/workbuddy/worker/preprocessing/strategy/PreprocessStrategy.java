package com.workbuddy.worker.preprocessing.strategy;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.enums.preprocess.PreprocessAction;
import com.workbuddy.common.enums.preprocess.PreprocessParam;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.StageStrategy;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 预处理策略快照（结构化模型）：类型/名称/版本 + 七规则配置（rules）+ 自定义规则组（custom）。
 * 触发时快照进任务，执行只用快照（可复现）；解析时由 {@link PreprocessAlgorithmSpec#normalize} 补全缺省规则与默认参数。
 * 旧扁平格式（options 平铺）不再支持。
 */
@Data
public class PreprocessStrategy implements StageStrategy {

    /** 策略类型 */
    public static final String TYPE = "PREPROCESS";

    /** 开关取值（JSON 契约） */
    public static final String ON = "ON";
    public static final String OFF = "OFF";

    /** 自定义规则组的快照键（JSON 契约）：与七条固定规则同级，不进 rules */
    public static final String CUSTOM_KEY = "custom";

    /** 策略类型（PREPROCESS） */
    private String type = TYPE;

    /** 策略名 */
    private String name;

    /** 版本号 */
    private String version;

    /** 七规则配置（键 = PreprocessRule.key()） */
    private Map<String, PreprocessRuleConfig> rules = new HashMap<>();

    /** 自定义规则组（链尾执行，见 CustomCleanRule） */
    private CustomRuleGroup custom = new CustomRuleGroup();

    /** 取某规则配置（解析后必存在；缺失返回 null）。 */
    public PreprocessRuleConfig rule(PreprocessRule rule) {
        return NullUtil.isNull(rules) || NullUtil.isNull(rule) ? null : rules.get(rule.key());
    }

    /** 三态规则处置方式（action 缺省时按旧开关换算，再缺回退默认）。 */
    public String action(PreprocessRule rule, String defaultValue) {
        PreprocessRuleConfig config = rule(rule);
        if (NullUtil.isNull(config)) {
            return defaultValue;
        }
        if (StrUtil.isNotBlank(config.getAction())) {
            return config.getAction();
        }
        String legacy = legacyAction(rule, config.getEnabled());
        return StrUtil.isBlank(legacy) ? defaultValue : legacy;
    }

    /** 旧开关格式（enabled）→ 三态处置：ON=EXCLUDE / OFF=KEEP；非旧开关规则或开关为空返回 null。 */
    public static String legacyAction(PreprocessRule rule, String enabled) {
        if (NullUtil.isNull(rule) || !rule.legacyToggle() || StrUtil.isBlank(enabled)) {
            return null;
        }
        return ON.equalsIgnoreCase(enabled) ? PreprocessAction.EXCLUDE.name() : PreprocessAction.KEEP.name();
    }

    /** 开关规则取值（缺失回退默认）。 */
    public boolean enabled(PreprocessRule rule, boolean defaultValue) {
        PreprocessRuleConfig config = rule(rule);
        String value = NullUtil.isNull(config) ? null : config.getEnabled();
        return StrUtil.isBlank(value) ? defaultValue : ON.equalsIgnoreCase(value);
    }

    /** 规则参数字符串值（缺失回退默认）。 */
    public String param(PreprocessRule rule, String key, String defaultValue) {
        PreprocessRuleConfig config = rule(rule);
        String value = NullUtil.isNull(config) || NullUtil.isNull(config.getParams())
                ? null : config.getParams().get(key);
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    /** 规则数值参数（缺失/非法回退默认）。 */
    public int intParam(PreprocessRule rule, PreprocessParam param, int defaultValue) {
        String value = param(rule, param.key(), String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 规则开关参数（缺失/非法回退默认）。 */
    public boolean boolParam(PreprocessRule rule, PreprocessParam param, boolean defaultValue) {
        String value = param(rule, param.key(), defaultValue ? ON : OFF);
        return ON.equalsIgnoreCase(value);
    }

    /** 生效的自定义规则列表（总开关 OFF 或无规则 → 空列表）。 */
    public List<PreprocessCustomRule> customRules() {
        if (NullUtil.isNull(custom) || !ON.equalsIgnoreCase(custom.getEnabled())
                || NullUtil.isNull(custom.getRules())) {
            return List.of();
        }
        return custom.getRules();
    }

    /** 完整版本串（name-version，如 preproc-default-v1）。 */
    public String fullVersion() {
        return name + "-" + version;
    }
}
