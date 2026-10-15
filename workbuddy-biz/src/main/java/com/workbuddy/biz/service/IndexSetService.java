package com.workbuddy.biz.service;

import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.worker.indexing.BuildOrder;

import java.util.List;

/**
 * 索引构建与发布控制面（策略集合模型）：
 * 一个组合 = 一个 Milvus 集合（kb_{kbId}_{versionNo}），集合只增不改；
 * 发布/回退 = MySQL 指针切换；回收 = drop 集合即时释放。
 * 行写入由文件产物就绪回调按组合追加（文件即版本），构建任务承担"注册组合 + 全量对账 + 发布判定"。
 *
 * @author cxxl
 */
public interface IndexSetService {

    /**
     * 文件产物就绪回调（EMBED 任务成功后调用）：血缘解析文件产物组合 →
     * 定位/复用组合集合（同组合活跃版本复用并追加；**绑定开（生产就绪）才自动注册新组合**，
     * 绑定关=评测模式不自动注册，评测索引构建走手动 buildCandidate）→ 追加（upsert 幂等）→
     * 活账本统计更新 → 批次对账 → READY（状态迁移无条件）→ 绑定开且产物组合==绑定组合 → 自动发布 →
     * 产物组合 ≠ 在线组合 → 按在线组合拓扑重放补齐该文件（文件永不丢）；
     * 评测冻结集（LIST）路由与规则三补齐不受注册开关影响，照常执行。
     */
    void onFileProductsReady(Long fileResultId);

    /**
     * 显式注册/构建组合候选：组合映射缺省按 KB 绑定补齐（绑定不齐全 40444）→
     * 同组合活跃版本防重（40443）→ 建版本行（CREATED）+ BUILD_INDEX 任务入队
     * （任务执行全量对账：完整性/维度/血缘 → 集合生命周期 → 一致性 → READY；发布判定按触发类型）。
     * 注册不要求产物齐（切策略场景注册先行、文件重跑累积；缺口由对账任务 FAILED 呈现）。
     */
    IndexBuildTriggerVO buildCandidate(BuildOrder order);

    /**
     * 发布（原子切换）：校验 READY → 单事务三级指针（版本 ONLINE + publishedAt/By
     * + 集合二级指针 + 知识库一级指针首次置位）+ 旧在线版退役 + 审计。
     */
    void publish(Long versionId);

    /**
     * 回退（指针切回 + 拓扑重放补齐）：指针切回目标版本（同事务，旧在线退役、目标复活 ONLINE）+ 审计；
     * 补齐：差异文件（缺目标组合血缘匹配产物）按目标组合从缺失的最上游环节重跑
     * （缺预处理投预处理；缺切片投切片且显式绑定上游预处理产物；向量化仍手动逐环节，
     * 完成后 onFileProductsReady 追加闭环）；无差异文件直接投递 COMPENSATE 构建（对账 + 恒自动发布）。
     */
    void rollback(Long versionId);

    /**
     * 回收组合集合：禁删在线版（40442）/构建中（40443）/退役版探索期保留 →
     * drop 集合即时物理释放 → 审计 → 删行。
     */
    void recycle(Long versionId);

    /**
     * 组合列表（新→旧，含在线标记/集合名/维度/活账本统计；无集合/无版本 → 空列表）。
     */
    List<IndexVersionVO> listVersions(Long knowledgeBaseId);

    /**
     * 组合详情（stage→strategy 映射展开 + 统计 + 状态 + 在线标记；无 → 40441）。
     */
    IndexVersionVO versionDetail(Long knowledgeBaseId, Long versionId);

    /**
     * 可构建组合枚举清单（测评模式构建弹窗数据源：组合 + 产物完整标记 + 文件数 × 向量数预览）；
     * fileResultIds 空 → 全库口径。
     */
    List<IndexComboVO> listCombos(Long knowledgeBaseId, List<Long> fileResultIds);

    /**
     * 组合验证：全量对账（血缘感知 expected 实时重算）+ 抽样检索冒烟（向量 1 次 + 全文 1 次）；
     * 仅 READY/ONLINE/RETIRED 可验证（其余 40443）。
     */
    IndexValidateVO validate(Long versionId);
}
