package com.workbuddy.biz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.dto.request.input.FileSubmitDTO;
import com.workbuddy.common.dto.request.knowledge.FileResultQueryDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseQueryDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.SubmitLogQueryDTO;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;

import java.util.List;

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
 *
 * @author cxxl
 */
public interface KnowledgeBaseService {

    /**
     * 创建知识库（默认启用状态），返回知识库 ID。
     */
    Long create(KnowledgeBaseCreateDTO dto);

    /**
     * 更新名称/描述/策略绑定开关。
     */
    @SuppressWarnings("SameReturnValue")
    boolean update(KnowledgeBaseUpdateDTO dto);

    /**
     * 详情（已逻辑删除不可见）。
     */
    KnowledgeBaseVO detail(Long id);

    /**
     * 分页列表（**仅当前用户可见的库**；名称模糊，不含已删），并回填每库文档数与已发布索引版本。
     *
     * @param query 查询条件（名称 / 状态 / 排序都可留空，页码与页长口径见 {@link KnowledgeBaseQueryDTO} 父类；
     *              排序未知取值按默认口径）
     * @return 知识库分页
     */
    IPage<KnowledgeBaseVO> page(KnowledgeBaseQueryDTO query);

    /**
     * 统计概览（仅未删除数据）：知识库总数、启用数、文档数。
     *
     * <p>三个数与 {@link #page} **同一可见范围**：文档数先取可见库 ID 再按集合计数
     * （`wb_file_result` 没有归属列）。
     */
    KnowledgeBaseStatsVO stats();

    /**
     * 停用（仅启用状态可停用）。
     */
    @SuppressWarnings("SameReturnValue")
    boolean disable(Long id);

    /**
     * 启用（仅停用状态可启用）。
     */
    @SuppressWarnings("SameReturnValue")
    boolean enable(Long id);

    /**
     * 逻辑删除（del_flag='1'，无恢复接口）。
     */
    @SuppressWarnings("SameReturnValue")
    boolean delete(Long id);

    /**
     * 查知识库某类型的策略绑定（未绑定返回仅含 strategyType 的空 VO）。
     */
    StrategyBindingVO strategyBinding(Long id, String strategyType);

    /**
     * 批量查某策略类型下**所有已绑定的知识库**（一次查询替代按库逐个查）。
     *
     * <p>绑定按知识库维度存储，"这个策略版本被哪些库绑了"是反方向的问题；本方法用一条 SQL
     * 取回该策略类型下的全部绑定行，替代按库逐个查。
     *
     * <p>只返回**确实绑定了启用中版本**的行：版本被删或已停用时跳过（与单体查询
     * {@link #strategyBinding} 的口径一致 —— 它查不到版本时也只返回 strategyType）。
     *
     * @param strategyType 策略类型，须在可绑定白名单内（白名单外 40001）
     * @return 每条含 knowledgeBaseId / strategyVersionId / 策略名与版本号；无绑定时空列表
     */
    List<StrategyBindingVO> strategyBindings(String strategyType);

    /**
     * 绑定/解绑知识库某类型策略：strategyVersionId 为 null 解绑（逻辑删除，重绑复用原行）；
     * 非法（类型白名单/版本不存在/类型不匹配/停用/绑定开关关闭）→ 40001。
     */
    @SuppressWarnings("SameReturnValue")
    boolean bindStrategy(Long id, StrategyBindingUpdateDTO dto);

    /**
     * 批量设置知识库策略集合（发布=知识库策略集合）：一次调用给全可绑定类型（幂等设置），
     * strategyVersionId 为 null = 解绑；逐类型审计 BIND，任一项失败整体回滚。
     */
    @SuppressWarnings("SameReturnValue")
    boolean bindStrategies(Long id, StrategyBindingsUpdateDTO request);

    // ------------------------------------------------------------------
    // 文档输入链路（提交建档 / 提交记录 / 文件结果）
    // ------------------------------------------------------------------

    /**
     * 提交文件：幂等检查 → 归属校验 → 文件校验 → 建档三写
     * （wb_source_file / wb_file_result / wb_submit_log，同一事务）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param request         提交请求（fileId + requestId）
     * @return 提交响应：新建提交 pipelineTaskId 为 null；
     *         幂等回放时回填该文件结果已有的解析任务 ID（可能为 null）
     * @apiNote 失败语义：知识库不存在/未启用抛 KnowledgeException（40401/40421）；
     *          幂等键缺失抛 KnowledgeException（40420）；
     *          文件校验不通过（4041x，含文件不存在）不抛异常，记 FAIL 提交日志并正常返回。
     */
    FileSubmitVO submit(Long knowledgeBaseId, FileSubmitDTO request);

    /**
     * 提交记录分页查询：数据源 wb_submit_log（PASS/FAIL 均记录），
     * 支持按提交结果与文件名模糊筛选。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件（状态 / 文件名都可留空，页码与页长口径见 {@link SubmitLogQueryDTO} 父类）
     * @return 提交记录分页 VO（含失败原因）
     */
    IPage<SubmitLogVO> pageSubmitLogs(Long knowledgeBaseId, SubmitLogQueryDTO query);

    /**
     * 文件结果分页查询（执行链工作台列表数据源）：每行附带各环节最新任务状态。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件（环节可留空，页码与页长口径见 {@link FileResultQueryDTO} 父类；
     *                        环节合法值 PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED，非法值 40001）
     * @return 文件结果分页 VO（含来源文件信息与各环节状态列表）
     */
    IPage<FileResultVO> pageFileResults(Long knowledgeBaseId, FileResultQueryDTO query);
}
