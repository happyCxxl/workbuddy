package com.workbuddy.common.enums.preprocess;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.utils.NullUtil;

/**
 * 预处理参数键目录：每条参数带规则归属、范围与默认值。
 * 数值参数（numeric，min/max 非空）与开关参数（ON/OFF）两类；保存校验与前端表单范围均以此为准。
 */
public enum PreprocessParam {

    /** 目录页判定：该页 TOC_LINE 元素数下限 */
    TOC_MIN_LINES_PER_PAGE(PreprocessRule.TOC, "minLinesPerPage", 1, 50, "3", "目录页判定：该页 TOC_LINE 元素数下限（调大更严格）"),

    /** Word 无页概念：连续 TOC_LINE 元素数下限 */
    TOC_RUN_MIN_LENGTH(PreprocessRule.TOC, "runMinLength", 1, 50, "3", "Word 无页概念：连续 TOC_LINE 元素数下限（调大更严格）"),

    /** 金额标准化 */
    FIELD_AMOUNT(PreprocessRule.FIELD, "amount", null, null, "ON", "金额标准化（千分位去逗号/中文大写金额提取）"),

    /** 日期标准化 */
    FIELD_DATE(PreprocessRule.FIELD, "date", null, null, "ON", "日期标准化（中文/分隔符日期统一 ISO）"),

    /** 面积标准化 */
    FIELD_AREA(PreprocessRule.FIELD, "area", null, null, "ON", "面积标准化（数值 + 单位平方米）"),

    /** 证书号提取 */
    FIELD_CERT_NO(PreprocessRule.FIELD, "certNo", null, null, "ON", "证书号/注册号提取（原文不变）"),

    /** 空白归一化 */
    TIDY_WHITESPACE(PreprocessRule.TIDY, "whitespace", null, null, "ON", "行内空白折叠/trim/单元格空白归一化"),

    /** 折行段落合并 */
    TIDY_JOIN_LINES(PreprocessRule.TIDY, "joinLines", null, null, "ON", "折行段落合并（同段内硬折行合成一行，段间不动）"),

    /** 标点与引号统一 */
    TIDY_PUNCT(PreprocessRule.TIDY, "punct", null, null, "ON", "重复标点折叠 + 全半角与弯引号统一"),

    /** 断词连字符合并 */
    TIDY_DASHES(PreprocessRule.TIDY, "dashes", null, null, "ON", "软连字符剔除 + 英文断行连字符合并"),

    /** 项目符号整理 */
    TIDY_BULLETS(PreprocessRule.TIDY, "bullets", null, null, "ON", "项目符号后补空格"),

    /** 移除 URL/邮箱 */
    TIDY_URLS(PreprocessRule.TIDY, "urls", null, null, "ON", "移除文本中的 URL 与邮箱地址");

    private final PreprocessRule rule;
    private final String key;
    private final Integer min;
    private final Integer max;
    private final String defaultValue;
    private final String desc;

    PreprocessParam(PreprocessRule rule, String key, Integer min, Integer max, String defaultValue, String desc) {
        this.rule = rule;
        this.key = key;
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        this.desc = desc;
    }

    /** 归属规则。 */
    public PreprocessRule rule() {
        return rule;
    }

    /** 策略快照中的序列化键（JSON 契约）。 */
    public String key() {
        return key;
    }

    /** 数值参数的下限（开关参数为 null）。 */
    public Integer min() {
        return min;
    }

    /** 数值参数的上限（开关参数为 null）。 */
    public Integer max() {
        return max;
    }

    /** 默认值（字符串；数值参数同样以字符串存）。 */
    public String defaultValue() {
        return defaultValue;
    }

    /** 参数说明（人类可读）。 */
    public String desc() {
        return desc;
    }

    /** 是否数值参数（开关参数为 ON/OFF）。 */
    public boolean numeric() {
        return NullUtil.isNotNull(min);
    }

    /** 按 (规则, 键) 精确查找；未识别返回 null。 */
    public static PreprocessParam of(PreprocessRule rule, String key) {
        if (NullUtil.isNull(rule) || StrUtil.isBlank(key)) {
            return null;
        }
        for (PreprocessParam param : values()) {
            if (param.rule == rule && param.key.equals(key)) {
                return param;
            }
        }
        return null;
    }
}
