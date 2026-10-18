package com.workbuddy.biz.service;

import com.workbuddy.worker.indexing.ComboSnapshot;

/**
 * 索引组合口径服务：
 * 绑定开启=KB 绑定策略集合（单组合）；绑定关闭=自动枚举「产物完整组合」（枚举口径见实现类）。
 * 产物取用口径：同策略多跑取该策略最新成功运行。
 */
public interface IndexComboService {

    /**
     * 绑定开启场景：按 KB 绑定策略集合取单组合（CHUNK/EMBED 绑定齐全才有；缺任一 → null）。
     */
    ComboSnapshot resolveBoundCombo(Long knowledgeBaseId);
}
