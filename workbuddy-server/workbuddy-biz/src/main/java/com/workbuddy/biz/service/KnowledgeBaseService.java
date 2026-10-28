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
 * 知识库应用门面：对 Controller 暴露 API 级用例，组合管理、绑定、文档输入三个内部服务。
 *
 * <p>本接口不继承内部用例接口，避免门面 Bean 同时成为多个服务类型的候选；
 * 具体事务、归属校验与审计由被组合的实现负责。
 */
public interface KnowledgeBaseService {

    /**
     * 创建知识库。
     *
     * @param dto 新建请求
     * @return 新知识库 ID
     */
    Long createKnowledgeBase(KnowledgeBaseCreateDTO dto);

    /**
     * 更新知识库。
     *
     * @param dto 修改请求
     * @return 成功为 true
     */
    boolean updateKnowledgeBase(KnowledgeBaseUpdateDTO dto);

    /**
     * 查询知识库详情。
     *
     * @param id 知识库 ID
     * @return 知识库详情
     */
    KnowledgeBaseVO getKnowledgeBase(Long id);

    /**
     * 分页查询知识库。
     *
     * @param query 查询条件
     * @return 知识库分页
     */
    IPage<KnowledgeBaseVO> pageKnowledgeBases(KnowledgeBaseQueryDTO query);

    /**
     * 查询知识库统计概览。
     *
     * @return 统计视图
     */
    KnowledgeBaseStatsVO getKnowledgeBaseStats();

    /**
     * 停用知识库。
     *
     * @param id 知识库 ID
     * @return 成功为 true
     */
    boolean disableKnowledgeBase(Long id);

    /**
     * 启用知识库。
     *
     * @param id 知识库 ID
     * @return 成功为 true
     */
    boolean enableKnowledgeBase(Long id);

    /**
     * 逻辑删除知识库。
     *
     * @param id 知识库 ID
     * @return 成功为 true
     */
    boolean deleteKnowledgeBase(Long id);

    /**
     * 查询知识库某类型的策略绑定。
     *
     * @param id           知识库 ID
     * @param strategyType 策略类型
     * @return 绑定视图
     */
    StrategyBindingVO getKnowledgeBaseStrategyBinding(Long id, String strategyType);

    /**
     * 批量查询某策略类型下已绑定的知识库。
     *
     * @param strategyType 策略类型
     * @return 绑定视图列表
     */
    List<StrategyBindingVO> listKnowledgeBaseStrategyBindings(String strategyType);

    /**
     * 绑定或解绑知识库某类型策略。
     *
     * @param id  知识库 ID
     * @param dto 绑定请求
     * @return 成功为 true
     */
    boolean bindKnowledgeBaseStrategy(Long id, StrategyBindingUpdateDTO dto);

    /**
     * 批量设置知识库策略集合。
     *
     * @param id      知识库 ID
     * @param request 批量绑定请求
     * @return 成功为 true
     */
    boolean bindKnowledgeBaseStrategies(Long id, StrategyBindingsUpdateDTO request);

    /**
     * 提交知识库文件。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param request         提交请求
     * @return 提交响应
     */
    FileSubmitVO submitKnowledgeBaseFile(Long knowledgeBaseId, FileSubmitDTO request);

    /**
     * 分页查询知识库提交记录。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件
     * @return 提交记录分页
     */
    IPage<SubmitLogVO> pageKnowledgeBaseSubmitLogs(Long knowledgeBaseId, SubmitLogQueryDTO query);

    /**
     * 分页查询知识库文件结果。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件
     * @return 文件结果分页
     */
    IPage<FileResultVO> pageKnowledgeBaseFileResults(Long knowledgeBaseId, FileResultQueryDTO query);
}
