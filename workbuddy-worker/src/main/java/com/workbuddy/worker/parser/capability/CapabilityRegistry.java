package com.workbuddy.worker.parser.capability;

/**
 * 能力注册表：候选 = Spring Bean 集合 ∩ 注册表 enabled ∩ 配置项开关；
 * 无启用实现返回 null（主流程走内置降级）。
 * 主流程只依赖本接口，不感知具体实现类。
 *
 * @author cxxl
 */
public interface CapabilityRegistry {

    /** 启用的结构化 OCR 实现；无 → null */
    StructuredOcrProvider ocr();

    /** 启用的版面分析实现；无 → null */
    LayoutProvider layout();

    /** 启用的表格结构识别实现；无 → null */
    TableStructureProvider table();
}
