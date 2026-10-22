package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.biz.service.KnowledgeBaseBindingService;
import com.workbuddy.biz.service.KnowledgeBaseCrudService;
import com.workbuddy.biz.service.KnowledgeBaseFileService;
import com.workbuddy.biz.service.KnowledgeBaseService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库应用服务门面：对 Controller 暴露 API 级方法，按管理、绑定、文档输入三块委托给对应用例实现。
 *
 * <p>本类不承载业务编排与事务边界，事务注解落在被委托的实现上；对外契约由
 * {@link KnowledgeBaseService} 独立声明，不与内部用例接口形成继承关系。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseCrudService crudService;

    private final KnowledgeBaseBindingService bindingService;

    private final KnowledgeBaseFileService fileService;

    /**
     * 创建知识库（默认启用状态），返回知识库 ID。
     *
     * @param dto 新建请求
     * @return 新知识库 ID
     */
    @Override
    public Long createKnowledgeBase(KnowledgeBaseCreateDTO dto) {
        return crudService.create(dto);
    }

    /**
     * 更新名称/描述/策略绑定开关。
     *
     * @param dto 修改请求
     * @return 更新成功为 true
     */
    @Override
    public boolean updateKnowledgeBase(KnowledgeBaseUpdateDTO dto) {
        return crudService.update(dto);
    }

    /**
     * 知识库详情（已逻辑删除不可见）。
     *
     * @param id 知识库 ID
     * @return 知识库详情
     */
    @Override
    public KnowledgeBaseVO getKnowledgeBase(Long id) {
        return crudService.detail(id);
    }

    /**
     * 分页列表（仅当前用户可见的库），回填文档数、已发布索引版本与策略绑定摘要。
     *
     * @param query 查询条件
     * @return 知识库分页
     */
    @Override
    public IPage<KnowledgeBaseVO> pageKnowledgeBases(KnowledgeBaseQueryDTO query) {
        return crudService.page(query);
    }

    /**
     * 统计概览（仅未删除数据）：知识库总数、启用数、文档数。
     *
     * @return 统计视图
     */
    @Override
    public KnowledgeBaseStatsVO getKnowledgeBaseStats() {
        return crudService.stats();
    }

    /**
     * 停用知识库（仅启用状态可停用）。
     *
     * @param id 知识库 ID
     * @return 变更成功为 true
     */
    @Override
    public boolean disableKnowledgeBase(Long id) {
        return crudService.disable(id);
    }

    /**
     * 启用知识库（仅停用状态可启用）。
     *
     * @param id 知识库 ID
     * @return 变更成功为 true
     */
    @Override
    public boolean enableKnowledgeBase(Long id) {
        return crudService.enable(id);
    }

    /**
     * 逻辑删除知识库（del_flag='1'，无恢复接口）。
     *
     * @param id 知识库 ID
     * @return 删除成功为 true
     */
    @Override
    public boolean deleteKnowledgeBase(Long id) {
        return crudService.delete(id);
    }

    /**
     * 查知识库某类型的策略绑定（未绑定返回仅含 strategyType 的空 VO）。
     *
     * @param id           知识库 ID
     * @param strategyType 策略类型
     * @return 绑定视图
     */
    @Override
    public StrategyBindingVO getKnowledgeBaseStrategyBinding(Long id, String strategyType) {
        return bindingService.strategyBinding(id, strategyType);
    }

    /**
     * 批量查某策略类型下所有已绑定的知识库。
     *
     * @param strategyType 策略类型
     * @return 绑定视图列表
     */
    @Override
    public List<StrategyBindingVO> listKnowledgeBaseStrategyBindings(String strategyType) {
        return bindingService.strategyBindings(strategyType);
    }

    /**
     * 绑定/解绑知识库某类型策略（strategyVersionId 为 null 解绑）。
     *
     * @param id  知识库 ID
     * @param dto 绑定请求
     * @return 成功为 true
     */
    @Override
    public boolean bindKnowledgeBaseStrategy(Long id, StrategyBindingUpdateDTO dto) {
        return bindingService.bindStrategy(id, dto);
    }

    /**
     * 批量设置知识库策略集合（幂等设置，任一项失败整体回滚）。
     *
     * @param id      知识库 ID
     * @param request 批量绑定请求
     * @return 成功为 true
     */
    @Override
    public boolean bindKnowledgeBaseStrategies(Long id, StrategyBindingsUpdateDTO request) {
        return bindingService.bindStrategies(id, request);
    }

    /**
     * 提交文件：幂等检查后建档三写（同一短事务），解析由页面后续手动触发。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param request         提交请求（fileId + requestId）
     * @return 提交响应
     */
    @Override
    public FileSubmitVO submitKnowledgeBaseFile(Long knowledgeBaseId, FileSubmitDTO request) {
        return fileService.submit(knowledgeBaseId, request);
    }

    /**
     * 提交记录分页查询。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件
     * @return 提交记录分页
     */
    @Override
    public IPage<SubmitLogVO> pageKnowledgeBaseSubmitLogs(Long knowledgeBaseId, SubmitLogQueryDTO query) {
        return fileService.pageSubmitLogs(knowledgeBaseId, query);
    }

    /**
     * 文件结果分页查询（每行附带各环节最新任务状态）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件
     * @return 文件结果分页
     */
    @Override
    public IPage<FileResultVO> pageKnowledgeBaseFileResults(Long knowledgeBaseId, FileResultQueryDTO query) {
        return fileService.pageFileResults(knowledgeBaseId, query);
    }
}
