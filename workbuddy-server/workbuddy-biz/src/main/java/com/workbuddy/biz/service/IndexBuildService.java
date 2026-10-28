package com.workbuddy.biz.service;

import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.worker.indexing.BuildOrder;

/**
 * 索引构建用例：产物就绪追加、候选注册构建、候选校验与回退补齐。
 * 只负责构建侧编排，发布交由 {@link IndexPublishService}，版本回退由 {@link IndexVersionService} 驱动。
 */
public interface IndexBuildService {

    /**
     * 文件产物就绪回调：血缘解析产物组合 → 定位/复用组合集合（绑定开才自动注册）→ 追加 →
     * 活账本与批次对账 → READY 与自动发布判定 → 按在线组合拓扑补齐 → 评测冻结集追加。
     *
     * @param fileResultId 文件结果 ID
     */
    void onFileProductsReady(Long fileResultId);

    /**
     * 显式注册/构建组合候选：组合映射缺省按知识库绑定补齐 → 同组合活跃版本防重 →
     * 建版本行（CREATED）+ BUILD_INDEX 任务入队。
     *
     * @param order 构建命令
     * @return 版本 ID、版本号与任务 ID
     */
    IndexBuildTriggerVO buildCandidate(BuildOrder order);

    /**
     * 校验候选版本：组合实时对账 + 向量/全文抽样冒烟。
     *
     * @param versionId 版本 ID
     * @return 校验结果
     */
    IndexValidateVO validate(Long versionId);

    /**
     * 回退补齐（由回退事务内调用）：差异文件按目标组合拓扑重放；无差异文件投递 COMPENSATE 构建。
     *
     * @param set    索引集合
     * @param target 回退目标版本
     */
    void compensate(WbIndexSet set, WbIndexVersion target);
}
