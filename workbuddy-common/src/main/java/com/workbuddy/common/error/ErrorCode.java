package com.workbuddy.common.error;

import lombok.Getter;

/**
 * 统一错误码。
 *
 * <p>登记规则：新错误码必须在此枚举登记；编码分段：40001 参数、401xx 认证、402xx 限流、404xx 业务（40460~40469 模型调用）、40500 系统兜底。
 */
@Getter
public enum ErrorCode {

    /** 参数错误 */
    PARAM_INVALID(40001, "参数错误"),

    /** 未认证或令牌无效 */
    UNAUTHORIZED(40101, "未认证或令牌无效"),

    /** 用户名已存在 */
    USERNAME_EXISTS(40102, "用户名已存在"),

    /** 用户名或密码错误 */
    LOGIN_FAILED(40103, "用户名或密码错误"),

    /** 权限不足（当前角色不满足接口要求） */
    FORBIDDEN(40104, "权限不足，无法访问该功能"),

    /** 用户不存在（或已逻辑删除） */
    USER_NOT_FOUND(40105, "用户不存在"),

    /** 角色码值非法 */
    ROLE_INVALID(40106, "角色不合法"),

    /** 用户状态码值非法（只接受 1 启用 / 0 停用） */
    USER_STATUS_INVALID(40109, "用户状态不合法"),

    /** 不允许操作当前登录账号自身（避免管理员把自己锁在外面） */
    USER_SELF_OPERATION_FORBIDDEN(40107, "不能对当前登录账号执行该操作"),

    /** 系统需保留至少一个启用状态的管理员 */
    LAST_ADMIN_FORBIDDEN(40108, "系统需保留至少一个启用的管理员账号"),

    /** 当前密码不正确（修改密码时校验旧密码失败） */
    OLD_PASSWORD_MISMATCH(40110, "当前密码不正确"),

    /** 登录尝试过于频繁（连续失败达阈值，账号或来源 IP 被临时锁定） */
    LOGIN_TOO_FREQUENT(40111, "登录尝试过于频繁，请稍后再试"),

    /** 请求过于频繁（接口级限流：注解声明的窗口内次数已用尽） */
    RATE_LIMITED(40201, "请求过于频繁，请稍后再试"),

    /** 知识库不存在（或已逻辑删除） */
    KB_NOT_FOUND(40401, "知识库不存在"),

    /** 知识库状态不合法（非法启停/删除/状态迁移） */
    KB_STATUS_ILLEGAL(40402, "知识库状态不合法"),

    /** 文件不存在（文件档案查不到或取流失败） */
    FILE_NOT_FOUND(40410, "文件不存在"),

    /** 未传幂等键 */
    REQUEST_ID_MISSING(40420, "缺少幂等键"),

    /** 知识库未启用（不可提交） */
    KB_NOT_ACTIVE(40421, "知识库未启用，不可提交文档"),

    /** 任务进行中（同环节已有排队/执行中任务，拒绝重复触发） */
    TASK_ALREADY_PENDING(40431, "任务进行中，请勿重复触发"),

    /** 文件结果不存在 */
    FILE_RESULT_NOT_FOUND(40432, "文件结果不存在"),

    /** 策略版本不存在或未启用 */
    STRATEGY_VERSION_NOT_FOUND(40433, "策略版本不存在或未启用"),

    /** 切片最大片长超过模型窗口 */
    EMBED_MODEL_INCOMPATIBLE(40435, "切片最大片长超过模型窗口，请更换模型或调整切片策略"),

    /** 触发下游环节时未指定上游产物 */
    TASK_UPSTREAM_MISSING(40436, "未指定上游产物，请从上游产物的卡片发起触发"),

    /** 触发时未解析到可用策略（知识库未绑定且未显式指定） */
    TASK_STRATEGY_MISSING(40438, "未解析到可用策略，请绑定策略或显式选择后触发"),

    /** 上游产物的产出任务不是成功或部分成功（下游门禁拒绝消费） */
    TASK_UPSTREAM_NOT_READY(40439, "上游产物来自未成功的运行，请重新触发上游环节"),

    /** 索引版本不存在 */
    INDEX_VERSION_NOT_FOUND(40441, "索引版本不存在"),

    /** 在线发布版本禁止删除 */
    INDEX_ONLINE_DELETE_FORBIDDEN(40442, "在线发布版本禁止删除"),

    /** 索引版本构建中冲突 */
    INDEX_BUILDING_CONFLICT(40443, "该索引版本构建中，禁止重复操作"),

    /** 组合产物不完整 */
    INDEX_COMBO_INCOMPLETE(40444, "组合产物不完整，无法构建"),

    /** 未发布任何索引版本 */
    INDEX_NOT_PUBLISHED(40446, "未发布任何索引版本"),

    /** 索引集合 schema 与声明不一致（回读校验失败） */
    INDEX_COLLECTION_SCHEMA_MISMATCH(40447, "索引集合结构与声明不一致，请重建集合"),

    /** 索引组合快照缺失环节策略维度 */
    INDEX_COMBO_SNAPSHOT_LEGACY(40448, "索引组合快照缺失环节策略维度，请废弃重灌后重试"),

    /** 评测冻结集禁止发布/回退 */
    INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN(40449, "指定文件范围的评测冻结集禁止发布/回退"),

    /** 检索规则不存在（或类型非 RETRIEVAL） */
    RETRIEVAL_RULE_NOT_FOUND(40450, "检索规则不存在"),

    /** 该检索能力尚未启用（预留能力锁定） */
    RETRIEVAL_CAPABILITY_LOCKED(40451, "该检索能力尚未启用"),

    /** 策略已被知识库绑定，禁止物理删除 */
    STRATEGY_BOUND_DELETE_FORBIDDEN(40452, "策略已被知识库绑定，禁止删除（可停用代替）"),

    /** 记录所属的数据源与当前启用的数据源不一致（跨数据源继续执行被拒） */
    STORAGE_TYPE_MISMATCH(40453, "任务所属数据源与当前启用的数据源不一致，无法继续执行"),

    /** 产物对象在记录所属的存储中不存在 */
    STORAGE_OBJECT_MISSING(40454, "产物对象在所属存储中不存在"),

    /** 记录所属的存储类型没有实现的枚举值，或该类型未配置 */
    STORAGE_BACKEND_UNCONFIGURED(40455, "记录所属的存储后端未配置"),

    /** 存储数据源不存在（或已逻辑删除） */
    STORAGE_SOURCE_NOT_FOUND(40457, "存储数据源不存在"),

    /** 存储数据源状态不允许该操作（已停用的数据源不可设为当前启用） */
    STORAGE_SOURCE_ILLEGAL(40458, "存储数据源状态不允许该操作"),

    /** 存储数据源连接失败（设为当前启用的探活不通过） */
    STORAGE_SOURCE_PROBE_FAILED(40459, "存储数据源连接失败"),

    /** 系统异常 */
    SYSTEM_ERROR(40500, "系统异常，请稍后重试"),

    // ===== 模型调用（40460~40470）：只承载码值与文案，模态与厂商信息由异常 detail 补齐 =====

    /** 厂商未实现该模态 */
    MODEL_MODALITY_UNSUPPORTED(40460, "模型未支持该模态"),

    /** 配置里没有该厂商 */
    MODEL_VENDOR_UNKNOWN(40461, "模型厂商未配置"),

    /** 密钥无效或无权限 */
    MODEL_AUTH_FAILED(40462, "模型鉴权失败"),

    /** 参数错误或内容被审核拒绝 */
    MODEL_BAD_REQUEST(40463, "模型请求参数或内容被拒"),

    /** 厂商侧查不到该模型 */
    MODEL_NOT_FOUND(40464, "模型不存在"),

    /** 触发厂商限流 */
    MODEL_RATE_LIMITED(40465, "模型请求被限流"),

    /** 调用超时 */
    MODEL_TIMEOUT(40466, "模型请求超时"),

    /** 厂商服务端错误 */
    MODEL_SERVER_ERROR(40467, "模型服务端错误"),

    /** 网络不可达，含 DNS 解析失败与连接被拒 */
    MODEL_UNREACHABLE(40468, "模型服务不可达"),

    /** 输出未过结构校验 */
    MODEL_OUTPUT_INVALID(40469, "模型输出结构校验失败"),

    /** 候选全部熔断、冷却或容量不足，本次请求暂无可用模型 */
    MODEL_UNAVAILABLE(40470, "模型暂时不可用");

    private final int code;

    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
