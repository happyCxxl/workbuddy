package com.workbuddy.biz.service;

import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.dto.response.index.IndexVersionVO;

import java.util.List;

/**
 * 索引发布与查询用例：发布（单事务指针切换 + 退役 + 审计）、当前在线版本与版本列表/详情。
 * 不依赖构建与回退服务，保持发布边界的单向依赖。
 */
public interface IndexPublishService {

    /**
     * 发布（原子切换）：校验 READY → 单事务三级指针 + 旧在线版退役 + 审计。
     *
     * @param versionId 版本 ID
     */
    void publish(Long versionId);

    /**
     * 当前发布版本（versionNo → 集合名直连检索）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 当前在线版本；没有则为 null
     */
    WbIndexVersion currentPublished(Long knowledgeBaseId);

    /**
     * 列出知识库的索引版本（新→旧，含在线标记）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 索引版本列表；无集合时为空列表
     */
    List<IndexVersionVO> listVersions(Long knowledgeBaseId);

    /**
     * 索引版本详情（组合快照展开 + 统计 + 在线标记）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param versionId       版本 ID
     * @return 版本详情
     */
    IndexVersionVO versionDetail(Long knowledgeBaseId, Long versionId);
}
