package com.workbuddy.common.enums.knowledge;

/**
 * 审计动作类型（wb_audit_log.action_type）。
 *
 * @author cxxl
 */
public enum AuditActionType {

    /** 创建 */
    CREATE,

    /** 更新 */
    UPDATE,

    /** 停用 */
    DISABLE,

    /** 启用 */
    ENABLE,

    /** 策略绑定/解绑 */
    BIND,

    /** 逻辑删除 */
    DELETE,

    /** 索引发布 */
    PUBLISH_INDEX,

    /** 索引回退 */
    ROLLBACK_INDEX,

    /** 索引候选回收 */
    RECYCLE_INDEX,

    /** 检索规则选优发布 */
    RETRIEVAL_RULE_PUBLISH,

    /** 用户管理：新增账号 */
    USER_CREATE,

    /** 用户管理：更新账号（角色变更 / 状态变更 / 重置密码） */
    USER_UPDATE,

    /** 用户管理：停用账号 */
    USER_DISABLE,

    /** 用户管理：启用账号 */
    USER_ENABLE,

    /** 用户管理：删除账号（逻辑删除） */
    USER_DELETE,

    /** 账号本人：修改密码 */
    USER_PASSWORD_CHANGE,

    /** 存储数据源：切换当前启用的数据源 */
    STORAGE_SWITCH,

    /** 模型目录：新增模型 */
    MODEL_CREATE,

    /** 模型目录：修改模型（厂商、能力、长度、价格、限额） */
    MODEL_UPDATE,

    /** 模型目录：启用模型 */
    MODEL_ENABLE,

    /** 模型目录：停用模型 */
    MODEL_DISABLE
}
