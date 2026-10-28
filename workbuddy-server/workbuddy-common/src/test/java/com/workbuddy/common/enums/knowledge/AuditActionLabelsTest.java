package com.workbuddy.common.enums.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 审计动作中文名单测。
 *
 * <p>守护 {@code label} 的三条口径：登记过的动作回中文名、BIND 按前后摘要区分
 * 绑定/解绑、未登记动作回落码值本身（不猜名字）。
 */
class AuditActionLabelsTest {

    /**
     * 登记过的动作回中文名。
     *
     * <p>守护 {@code label} 的映射：映射缺失即失败（审计列表显示码值）。
     */
    @Test
    void labelShouldReturnChineseForRegisteredActions() {
        assertEquals("创建知识库", AuditActionLabels.label("CREATE", null));
        assertEquals("新增账号", AuditActionLabels.label("USER_CREATE", null));
        assertEquals("切换当前存储数据源", AuditActionLabels.label("STORAGE_SWITCH", null));
    }

    /**
     * 每个审计动作（BIND 除外）都有中文名。
     *
     * <p>守护 {@code LABELS} 映射表的完备性：新增动作漏登记即失败（回落码值本身就是信号）。
     */
    @Test
    void everyActionExceptBindShouldCarryChineseLabel() {
        for (AuditActionType type : AuditActionType.values()) {
            if (type == AuditActionType.BIND) {
                continue;
            }
            String label = AuditActionLabels.label(type.name(), null);
            assertFalse(label.isBlank() || label.equals(type.name()),
                    () -> type + " 缺中文名（回落成码值）");
        }
    }

    /**
     * BIND 按前后摘要区分：有摘要是绑定策略，空摘要是解绑策略。
     *
     * <p>守护 {@code label} 的 BIND 分支判定：把空摘要也判成绑定即失败（解绑记录显示错动作）。
     */
    @Test
    void bindLabelShouldFollowAfterSummaryPresence() {
        assertEquals("绑定策略", AuditActionLabels.label("BIND", "CHUNK=chunk-hybrid-v1"));
        assertEquals("解绑策略", AuditActionLabels.label("BIND", null));
        assertEquals("解绑策略", AuditActionLabels.label("BIND", ""));
    }

    /**
     * 未登记或空动作回落入参本身，不猜名字。
     *
     * <p>守护 {@code label} 的回落口径：未知码值抛错即失败（历史数据展示中断）。
     */
    @Test
    void labelShouldFallBackToRawActionForUnknown() {
        assertEquals("NO_SUCH_ACTION", AuditActionLabels.label("NO_SUCH_ACTION", null));
        assertNull(AuditActionLabels.label(null, null));
        assertEquals("", AuditActionLabels.label("", null));
    }
}
