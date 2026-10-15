package com.workbuddy.common.enums.knowledge;

import com.workbuddy.common.utils.NullUtil;

import java.util.Map;

/**
 * 审计动作的中文名（展示口径）。
 *
 * <p>中文名独立于 {@link AuditActionType}：枚举是**领域码值**（写入侧各业务 service 与审计监听器
 * 都用它），展示名只在读日志时要用。两者的映射只在本类一处，动作语义变更时各写入点不受影响。
 *
 * <p>作用域口径：码值是省事的（`CREATE` 这种通用词），同一个码在不同对象上语义不同
 * （知识库「创建」≠ 账号「新增」），这里给的是**知识库语境**下的说法；
 * 遇到账号类动作（USER_*）按各自语义单独列出。
 *
 * @author cxxl
 */
public final class AuditActionLabels {

    /** 动作 → 中文名；未列出的动作回落成码值本身 */
    private static final Map<AuditActionType, String> LABELS = Map.ofEntries(
            Map.entry(AuditActionType.CREATE, "创建知识库"),
            Map.entry(AuditActionType.UPDATE, "修改知识库"),
            Map.entry(AuditActionType.DISABLE, "停用知识库"),
            Map.entry(AuditActionType.ENABLE, "启用知识库"),
            Map.entry(AuditActionType.DELETE, "删除知识库"),
            Map.entry(AuditActionType.PUBLISH_INDEX, "发布索引"),
            Map.entry(AuditActionType.ROLLBACK_INDEX, "回退索引"),
            Map.entry(AuditActionType.RECYCLE_INDEX, "回收索引"),
            Map.entry(AuditActionType.RETRIEVAL_RULE_PUBLISH, "发布检索规则"),
            Map.entry(AuditActionType.USER_CREATE, "新增账号"),
            Map.entry(AuditActionType.USER_UPDATE, "修改账号"),
            Map.entry(AuditActionType.USER_DISABLE, "停用账号"),
            Map.entry(AuditActionType.USER_ENABLE, "启用账号"),
            Map.entry(AuditActionType.USER_DELETE, "删除账号"),
            Map.entry(AuditActionType.USER_PASSWORD_CHANGE, "修改密码"),
            Map.entry(AuditActionType.STORAGE_SWITCH, "切换当前存储数据源"),
            Map.entry(AuditActionType.MODEL_CREATE, "新增模型"),
            Map.entry(AuditActionType.MODEL_UPDATE, "修改模型"),
            Map.entry(AuditActionType.MODEL_ENABLE, "启用模型"),
            Map.entry(AuditActionType.MODEL_DISABLE, "停用模型"));

    /**
     * BIND 的中文名按对象类型分：绑策略与解绑都走 BIND，
     * 具体是绑定还是解绑看前后摘要（后者为空 = 解绑）。
     */
    private static final String BIND_LABEL = "绑定策略";
    private static final String UNBIND_LABEL = "解绑策略";

    private AuditActionLabels() {
    }

    /**
     * 动作中文名。
     *
     * @param action      动作类型名（库表 action_type）
     * @param afterSummary 变更后摘要；仅 BIND 用（为空表示解绑）
     * @return 中文名；未识别动作返回原码值
     */
    public static String label(String action, String afterSummary) {
        AuditActionType type = parse(action);
        if (NullUtil.isNull(type)) {
            return action;
        }
        if (type == AuditActionType.BIND) {
            // 解绑时 after 为空、before 形如 "CHUNK=旧值"
            return NullUtil.isNull(afterSummary) || afterSummary.isBlank() ? UNBIND_LABEL : BIND_LABEL;
        }
        return LABELS.getOrDefault(type, action);
    }

    private static AuditActionType parse(String action) {
        if (NullUtil.isNull(action) || action.isBlank()) {
            return null;
        }
        for (AuditActionType type : AuditActionType.values()) {
            if (type.name().equalsIgnoreCase(action)) {
                return type;
            }
        }
        return null;
    }
}
