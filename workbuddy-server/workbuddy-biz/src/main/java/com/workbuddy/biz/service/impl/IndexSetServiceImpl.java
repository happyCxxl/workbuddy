package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.IndexBuildService;
import com.workbuddy.biz.service.IndexComboQueryService;
import com.workbuddy.biz.service.IndexPublishService;
import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.biz.service.IndexVersionService;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.worker.indexing.BuildOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 索引构建与发布控制面门面：只按用例委托构建、发布、版本与组合查询服务，不承载业务判断。
 */
@Service
@RequiredArgsConstructor
public class IndexSetServiceImpl implements IndexSetService {

    private final IndexBuildService buildService;
    private final IndexPublishService publishService;
    private final IndexVersionService versionService;
    private final IndexComboQueryService comboQueryService;

    /**
     * 文件产物就绪回调。
     *
     * @param fileResultId 文件结果 ID
     */
    @Override
    public void onFileProductsReady(Long fileResultId) {
        buildService.onFileProductsReady(fileResultId);
    }

    /**
     * 注册并构建组合候选。
     *
     * @param order 构建命令
     * @return 版本 ID、版本号与任务 ID
     */
    @Override
    public IndexBuildTriggerVO buildCandidate(BuildOrder order) {
        return buildService.buildCandidate(order);
    }

    /**
     * 发布索引版本。
     *
     * @param versionId 版本 ID
     */
    @Override
    public void publish(Long versionId) {
        publishService.publish(versionId);
    }

    /**
     * 当前发布版本。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 当前在线版本；没有则为 null
     */
    public WbIndexVersion currentPublished(Long knowledgeBaseId) {
        return publishService.currentPublished(knowledgeBaseId);
    }

    /**
     * 回退到指定索引版本。
     *
     * @param versionId 版本 ID
     */
    @Override
    public void rollback(Long versionId) {
        versionService.rollback(versionId);
    }

    /**
     * 回收索引候选。
     *
     * @param versionId 版本 ID
     */
    @Override
    public void recycle(Long versionId) {
        versionService.recycle(versionId);
    }

    /**
     * 列出知识库的索引版本。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 索引版本列表
     */
    @Override
    public List<IndexVersionVO> listVersions(Long knowledgeBaseId) {
        return publishService.listVersions(knowledgeBaseId);
    }

    /**
     * 索引版本详情。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param versionId       版本 ID
     * @return 版本详情
     */
    @Override
    public IndexVersionVO versionDetail(Long knowledgeBaseId, Long versionId) {
        return publishService.versionDetail(knowledgeBaseId, versionId);
    }

    /**
     * 列出可构建的索引组合。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param fileResultIds   限定参与的文件结果 ID（可空）
     * @return 组合列表
     */
    @Override
    public List<IndexComboVO> listCombos(Long knowledgeBaseId, List<Long> fileResultIds) {
        return comboQueryService.listCombos(knowledgeBaseId, fileResultIds);
    }

    /**
     * 校验索引候选。
     *
     * @param versionId 版本 ID
     * @return 校验结果
     */
    @Override
    public IndexValidateVO validate(Long versionId) {
        return buildService.validate(versionId);
    }
}
