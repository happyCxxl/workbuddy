package com.workbuddy.biz.service;

/**
 * 知识库应用服务：知识库管理闭环（创建/更新/详情/分页/启停/逻辑删除 + 同事务审计）
 * + 文档输入编排（提交建档、提交记录、文件结果查询）。
 * 提交记录与文件结果都挂在知识库下，归属与生命周期由知识库决定。
 *
 * <p>**可见范围**：普通用户只操作自己创建的知识库，管理员不限于归属（见
 * {@link com.workbuddy.common.domain.rules.KnowledgeBaseRules#visibleOwnerId} 与
 * {@link com.workbuddy.common.domain.rules.KnowledgeBaseRules#checkAccessible}）。
 *
 * <ul>
 *   <li>分页与统计按当前用户的归属过滤 —— 接口**不接受**"创建人"参数，由前端传参决定查谁等于没做隔离；</li>
 *   <li>所有按 ID 的读写都会校验归属，**不可访问时按"不存在"处理（40401）**，
 *       不用 40104：抛"无权限"等于确认该库存在，会把别人的库 ID 变成可探测的信息；</li>
 *   <li>`create` 必须取得到当前用户（无认证上下文直接 40101），不落 `user_id` 为空的孤儿行。</li>
 * </ul>
 *
 * <p>**文档输入链路的关键语义**：
 * <ul>
 *   <li>幂等：以 requestId 为幂等键，重复请求返回首次提交记录，不重复建档；</li>
 *   <li>失败分级：知识库归属失败抛业务异常不记日志；
 *       文件校验不通过记 FAIL 提交日志并正常返回；</li>
 *   <li>提交成功只建档、不触发解析——后续环节由页面在文件结果页手动触发（手动逐环节口径）。</li>
 * </ul>
 */
public interface KnowledgeBaseService extends KnowledgeBaseCrudService,
        KnowledgeBaseBindingService, KnowledgeBaseFileService {
}
