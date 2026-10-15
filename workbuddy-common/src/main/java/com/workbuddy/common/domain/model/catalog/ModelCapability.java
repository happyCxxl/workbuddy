package com.workbuddy.common.domain.model.catalog;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 模型能力（目录侧取值，D26）：一个模型可以声明多个能力，候选集按能力筛。
 *
 * <p>落库形态是逗号分隔的多值字符串（wb_model.capabilities），取值即本枚举名；
 * 冷却 / 在途计数 / 延迟 EWMA / 限额都按模型算，本枚举只表达「这个模型能做什么」。
 *
 * @author cxxl
 */
public enum ModelCapability {

    /** 对话 */
    CHAT,

    /** 向量编码 */
    EMBEDDING,

    /** 文字识别 */
    OCR,

    /** 版面分析 */
    LAYOUT,

    /** 表格结构 */
    TABLE_STRUCTURE;

    /**
     * 解析逗号分隔的能力清单：空段与未知取值跳过，重复取值只留一个。
     *
     * @param text 逗号分隔的能力清单，可空
     * @return 能力集合（按取值出现顺序，可能为空集）
     */
    public static Set<ModelCapability> parse(String text) {
        Set<ModelCapability> capabilities = new LinkedHashSet<>();
        if (StrUtil.isBlank(text)) {
            return capabilities;
        }
        for (String item : text.split(",")) {
            ModelCapability capability = of(item);
            if (capability != null) {
                capabilities.add(capability);
            }
        }
        return capabilities;
    }

    /**
     * 按取值解析能力（大小写不敏感）。
     *
     * @param code 能力取值（可空/未知）
     * @return 匹配的能力；空值与未知取值返回 null
     */
    public static ModelCapability of(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (ModelCapability capability : values()) {
            if (capability.name().equalsIgnoreCase(code.trim())) {
                return capability;
            }
        }
        return null;
    }

    /**
     * 能力清单序列化成落库形态（逗号分隔，不留空格）。
     *
     * @param capabilities 能力集合，可空
     * @return 逗号分隔的清单；空集合返回空串
     */
    public static String join(Set<ModelCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ModelCapability capability : capabilities) {
            if (!text.isEmpty()) {
                text.append(',');
            }
            text.append(capability.name());
        }
        return text.toString();
    }
}
