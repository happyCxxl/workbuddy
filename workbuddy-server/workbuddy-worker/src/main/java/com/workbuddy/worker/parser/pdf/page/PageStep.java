package com.workbuddy.worker.parser.pdf.page;

import java.util.Set;

/**
 * 页面步骤契约：步骤只读写本步声明的 {@link PageContext} 字段，不直接调用其他步骤实现。
 */
public interface PageStep {

    /**
     * 稳定步骤名，用于启动校验与测试定位。
     *
     * @return 步骤名
     */
    String name();

    /**
     * 依赖的先前步骤名；启动校验发现缺失即失败。默认不依赖其他步骤。
     *
     * @return 依赖步骤名集合
     */
    default Set<String> requires() {
        return Set.of();
    }

    /**
     * 对页面工作区执行本步骤。
     *
     * @param context 页面工作区
     */
    void apply(PageContext context);
}
