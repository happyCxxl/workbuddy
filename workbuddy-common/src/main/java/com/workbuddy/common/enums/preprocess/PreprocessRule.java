package com.workbuddy.common.enums.preprocess;

import cn.hutool.core.util.StrUtil;

/**
 * 预处理规则目录：rules 映射的七个固定规则键。
 * 单一事实源：biz 保存校验、前端表单分组与置灰、解析补默认均以此为准。
 * 自定义规则（custom）是规则链上的第 8 个节点，但不进本目录（它携带用户自定义正则列表，见 worker CustomRuleGroup）。
 *
 * @author cxxl
 */
public enum PreprocessRule {

    /** 页眉页脚处置（三态） */
    HEADER_FOOTER("headerFooter", "页眉页脚元素处置：KEEP 保留 / MARK 保留但标注 / EXCLUDE 剔除出检索内容流"),

    /** 目录处置（三态） */
    TOC("toc", "目录元素处置：先按阈值（整页行数 / 连续窗口）判定目录，再按动作处理；阈值对所有动作都生效，非目录页的特征行不受影响"),

    /** 噪声处置（三态） */
    NOISE("noise", "噪声元素处置：KEEP 只统计不处置 / MARK 保留但标注 / EXCLUDE 剔除出检索内容流"),

    /** 重复处置（三态；迁移期兼容旧 enabled 开关） */
    REPEAT("repeat", "重复元素处置（判定来自组装环节结构标记，除首份外）：EXCLUDE 只保留首份 / MARK 保留但标注 / KEEP 不改动（重复内容留在检索内容流）"),

    /** 字段规范化（开关） */
    FIELD("field", "招投标字段字符级标准化（金额/日期/面积/证号，按字段类型子开关）"),

    /** 文本整理（开关） */
    TIDY("tidy", "文本级整理（不动结构）：子规则集可独立开关"),

    /** 编码清理（开关） */
    ENCODING("encoding", "乱码/编码残留清理（默认开）");

    private final String key;
    private final String desc;

    PreprocessRule(String key, String desc) {
        this.key = key;
        this.desc = desc;
    }

    /** 策略快照中的序列化键（JSON 契约） */
    public String key() {
        return key;
    }

    /** 规则说明（人类可读） */
    public String desc() {
        return desc;
    }

    /** 三态规则（有 action，无 enabled） */
    public boolean triState() {
        return this == HEADER_FOOTER || this == TOC || this == NOISE || this == REPEAT;
    }

    /**
     * 旧开关格式：该规则历史上只有 enabled 开关（迁移期仍接受只给 enabled 的配置，ON 按 EXCLUDE / OFF 按 KEEP 解释）。
     */
    public boolean legacyToggle() {
        return this == REPEAT;
    }

    /** 按序列化键精确查找；未识别返回 null */
    public static PreprocessRule of(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        for (PreprocessRule rule : values()) {
            if (rule.key.equals(key)) {
                return rule;
            }
        }
        return null;
    }
}
