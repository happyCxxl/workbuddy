package com.workbuddy.common.domain.model;

import com.workbuddy.common.enums.model.ModelCapability;
import lombok.Data;

import java.util.Set;

/**
 * 内存注册表行：一条录入模型的运行时视图。
 *
 * <p>biz 装载器把 wb_model 的启用行映射成本类型（能力 CSV 解析一次）；
 * 只带运行时要用的字段，限额 / 窗口 / 价格在接入对应功能时再加。
 */
@Data
public class RegisteredModel {

    /** 厂商码（knowledge.model.vendors 配置里的键） */
    private String vendorCode;

    /** 厂商侧模型名（目录内以 厂商码 + 模型名 定位一行，同名跨厂商是合法录入） */
    private String modelKey;

    /** 能力清单（录入行的逗号分隔已解析成集合） */
    private Set<ModelCapability> capabilities;
}
