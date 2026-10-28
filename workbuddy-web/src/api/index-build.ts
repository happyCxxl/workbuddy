import { http } from './http';
import type { IndexComboVO, IndexVersionVO } from '@/types/index-record';
import type { IndexBuildOrder, IndexBuildTriggerVO, IndexValidateVO } from '@/types/index-publish';

/**
 * 索引构建与发布接口（挂 `/knowledge-base/{id}/index-*`）。
 *
 * <p>三条必须记住的后端语义：
 *
 * <ol>
 *   <li>**发布是严格的「单在线」**：`wb_knowledge_index_set.current_published_version_id` 是唯一裁决点，
 *       发布时旧在线版本自动 RETIRED；回退要求当前已有一个在线版本（不满足时 40446）；</li>
 *   <li>**子集（LIST 范围）不可发布/回退**（40449）—— 业务规则：子集用于测试策略效果，
 *       每个知识库都是全量发布。发布/回退接口各自拦截，页面只按可用性置灰按钮；</li>
 *   <li>**在线版本禁删**（40442）、**构建中禁一切操作**（40443）。</li>
 * </ol>
 *
 * <p>注意 `http` 拦截器在非 0 码时**已经弹过后端 msg 并 reject 一个普通 Error（不带 code）**，
 * 响应体不带业务码；区分错误类型只能按 msg，或在入口处禁用非法操作。
 */

/** 查询某知识库的索引版本列表（新→旧） */
export async function getIndexVersions(knowledgeBaseId: string): Promise<IndexVersionVO[]> {
  const response = await http.get<IndexVersionVO[]>(
    `/knowledge-base/${knowledgeBaseId}/index-sets`,
  );
  return response.data;
}

/** 查询单个索引版本详情 */
export async function detailIndexVersion(
  knowledgeBaseId: string,
  versionId: string,
): Promise<IndexVersionVO> {
  const response = await http.get<IndexVersionVO>(
    `/knowledge-base/${knowledgeBaseId}/index-sets/${versionId}`,
  );
  return response.data;
}

/**
 * 枚举可构建的组合。
 *
 * <p>返回「组合 × 成员」口径：每个组合带 `fileResultIds` 成员清单，
 * 成员 = 该组合下有完整成功产物链的文件。没跑完环节的文件不属于任何组合、不会出现。
 *
 * @param fileResultIds 传入时把枚举范围收窄到这些文件；不传即全库
 */
export async function getIndexCombos(
  knowledgeBaseId: string,
  fileResultIds?: string[],
): Promise<IndexComboVO[]> {
  const response = await http.get<IndexComboVO[]>(
    `/knowledge-base/${knowledgeBaseId}/index-combos`,
    {
      params:
        fileResultIds && fileResultIds.length > 0 ? { fileResultIds: fileResultIds.join(',') } : {},
    },
  );
  return response.data;
}

/** 构建候选索引（后台任务，立即返回版本号与任务 ID，不阻塞） */
export async function addIndexBuild(
  knowledgeBaseId: string,
  order: Omit<IndexBuildOrder, 'knowledgeBaseId'>,
): Promise<IndexBuildTriggerVO> {
  const response = await http.post<IndexBuildTriggerVO>(
    `/knowledge-base/${knowledgeBaseId}/index-sets`,
    { knowledgeBaseId, ...order },
  );
  return response.data;
}

/** 校验索引版本（全量对账 + 向量/全文冒烟；仅 READY/ONLINE/RETIRED 可校验） */
export async function updateIndexVersionValidate(
  knowledgeBaseId: string,
  versionId: string,
): Promise<IndexValidateVO> {
  const response = await http.post<IndexValidateVO>(
    `/knowledge-base/${knowledgeBaseId}/index-sets/${versionId}/validate`,
  );
  return response.data;
}

/** 发布（原子切换指针，旧在线版本自动退役） */
export async function updateIndexVersionPublish(
  knowledgeBaseId: string,
  versionId: string,
): Promise<boolean> {
  const response = await http.post<boolean>(
    `/knowledge-base/${knowledgeBaseId}/index-sets/${versionId}/publish`,
  );
  return response.data;
}

/** 回退（指针切回 + 按差异文件拓扑重放补齐，是个后台任务） */
export async function updateIndexVersionRollback(
  knowledgeBaseId: string,
  versionId: string,
): Promise<boolean> {
  const response = await http.post<boolean>(
    `/knowledge-base/${knowledgeBaseId}/index-sets/${versionId}/rollback`,
  );
  return response.data;
}

/** 回收索引版本（物理 drop 集合 + 删行，不可逆；在线版本被后端拒绝 40442） */
export async function deleteIndexVersion(
  knowledgeBaseId: string,
  versionId: string,
): Promise<boolean> {
  const response = await http.delete<boolean>(
    `/knowledge-base/${knowledgeBaseId}/index-sets/${versionId}`,
  );
  return response.data;
}
