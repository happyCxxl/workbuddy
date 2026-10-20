package com.workbuddy.biz.service;

import com.workbuddy.common.dto.request.knowledge.StrategyBindingUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateDTO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;

import java.util.List;

/**
 * 知识库策略绑定用例：单类型绑定/解绑、批量设置知识库策略集合、单向与反向查询。
 *
 * <p>策略类型必须落在可绑定白名单内；非法（类型白名单/版本不存在/类型不匹配/停用/绑定开关关闭）
 * 统一 40001。批量设置为「整套覆盖」语义，任一项失败整体回滚。
 */
public interface KnowledgeBaseBindingService {

    /**
     * 查知识库某类型的策略绑定（未绑定返回仅含 strategyType 的空 VO）。
     *
     * @param id           知识库 ID
     * @param strategyType 策略类型码值
     * @return 绑定视图
     */
    StrategyBindingVO strategyBinding(Long id, String strategyType);

    /**
     * 批量查某策略类型下所有已绑定的知识库（一次查询替代按库逐个查）。
     *
     * <p>只返回确实绑定了启用中版本的行：版本被删或已停用时跳过。
     *
     * @param strategyType 策略类型，须在可绑定白名单内（白名单外 40001）
     * @return 每条含 knowledgeBaseId / strategyVersionId / 策略名与版本号；无绑定时空列表
     */
    List<StrategyBindingVO> strategyBindings(String strategyType);

    /**
     * 绑定/解绑知识库某类型策略：strategyVersionId 为 null 解绑（逻辑删除，重绑复用原行）。
     *
     * @param id  知识库 ID
     * @param dto 绑定请求
     * @return 成功为 true
     */
    @SuppressWarnings("SameReturnValue")
    boolean bindStrategy(Long id, StrategyBindingUpdateDTO dto);

    /**
     * 批量设置知识库策略集合：一次调用给全可绑定类型（幂等设置），每项都必须给出版本；
     * 逐类型审计 BIND，任一项失败整体回滚。
     *
     * @param id      知识库 ID
     * @param request 批量绑定请求
     * @return 成功为 true
     */
    @SuppressWarnings("SameReturnValue")
    boolean bindStrategies(Long id, StrategyBindingsUpdateDTO request);
}
