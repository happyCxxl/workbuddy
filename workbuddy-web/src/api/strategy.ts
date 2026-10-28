import { http } from './http';
import type { StrategyBinding, StrategyBindItem } from '@/types/pipeline';
import type { StrategyVersion } from '@/types/strategy';

/**
 * 策略版本接口。
 *
 * <p>注意后端语义（`StrategyVersionServiceImpl`）：
 *
 * <ul>
 *   <li>**策略版本行不可修改**：`updateStrategyVersion` 的语义是**复制新行**，
 *       旧行原样保留，返回的是**新行**；</li>
 *   <li>新版本默认 ACTIVE；</li>
 *   <li>撞唯一键（同类型 + 同名 + 同版本）→ 后端报 `40001`「同环节同名版本已存在」；</li>
 *   <li>删除被知识库绑定的版本 → 后端报 `40452`，应引导用户改用停用。</li>
 * </ul>
 */

/** 查询策略版本列表；type 按环节过滤（PREPROCESS/CHUNK/EMBED/RETRIEVAL） */
export async function getStrategyVersions(
  type: string,
  includeInactive = false,
): Promise<StrategyVersion[]> {
  const response = await http.get<StrategyVersion[]>('/strategy-versions', {
    params: { type, includeInactive },
  });
  return response.data;
}

/** 创建策略版本的入参 */
export interface StrategyVersionDraft {
  type: string;
  name: string;
  version: string;
  configSnapshot: string;
}

/**
 * 创建策略版本，返回新建的行。
 *
 * <p>类型只在创建时确定，复制时沿用旧行的类型（后端不允许改类型）。
 */
export async function addStrategyVersion(draft: StrategyVersionDraft): Promise<StrategyVersion> {
  const response = await http.post<StrategyVersion>('/strategy-versions', draft);
  return response.data;
}

/**
 * 复制为新版本（后端把「编辑」实现为复制新行）。
 *
 * @param id 要复制的源版本 ID
 * @param draft name/version/configSnapshot；type 不由前端传，后端沿用源行的类型
 * @returns 新建的版本行
 */
export async function updateStrategyVersion(
  id: string,
  draft: Omit<StrategyVersionDraft, 'type'>,
): Promise<StrategyVersion> {
  const response = await http.put<StrategyVersion>(`/strategy-versions/${id}`, draft);
  return response.data;
}

/** 启用策略版本（幂等） */
export async function updateStrategyVersionEnable(id: string): Promise<StrategyVersion> {
  const response = await http.post<StrategyVersion>(`/strategy-versions/${id}/enable`);
  return response.data;
}

/** 停用策略版本（幂等） */
export async function updateStrategyVersionDisable(id: string): Promise<StrategyVersion> {
  const response = await http.post<StrategyVersion>(`/strategy-versions/${id}/disable`);
  return response.data;
}

/**
 * 删除策略版本（物理删）。
 *
 * <p>被知识库绑定时后端报 `40452`（应提示改用停用）。
 */
export async function deleteStrategyVersion(id: string): Promise<boolean> {
  const response = await http.delete<boolean>(`/strategy-versions/${id}`);
  return response.data;
}

/**
 * 查询某知识库在某类型的策略绑定；未绑定时只有 strategyType、其余字段为 null。
 *
 * <p>**只在"确实只关心一个库"时用它。** 要回答「这个版本被哪些库绑了」，
 * 用 {@link getStrategyBindings}（批量）。
 */
export async function getStrategyBinding(
  knowledgeBaseId: string,
  strategyType: string,
): Promise<StrategyBinding> {
  const response = await http.get<StrategyBinding>(
    `/knowledge-base/${knowledgeBaseId}/strategy-binding`,
    { params: { strategyType } },
  );
  return response.data;
}

/**
 * 批量查询某策略类型下**所有已绑定的知识库**（一次请求）。
 *
 * <p>绑定按知识库维度存储，"某策略版本被哪些库绑了"是反方向的问题；
 * 后端用一条 SQL 取回该类型下的全部绑定行。
 *
 * <p>返回的每一项都带 `knowledgeBaseId`，可直接与策略版本 ID 比对得出"哪些库绑了它"。
 */
export async function getStrategyBindings(strategyType: string): Promise<StrategyBinding[]> {
  const response = await http.get<StrategyBinding[]>('/knowledge-base/strategy-bindings', {
    params: { strategyType },
  });
  return response.data;
}

/**
 * 绑定 / 解绑知识库策略。
 *
 * @param strategyVersionId 传 null 表示解绑
 */
export async function updateStrategyBinding(
  knowledgeBaseId: string,
  strategyType: string,
  strategyVersionId: string | null,
): Promise<boolean> {
  const response = await http.put<boolean>(`/knowledge-base/${knowledgeBaseId}/strategy-binding`, {
    strategyType,
    strategyVersionId,
  });
  return response.data;
}

/**
 * 一次设置知识库的整套策略绑定（对应后端「发布 = 知识库策略集合」口径）。
 *
 * <p>**必须给全可绑定类型且每项都有版本** —— 后端会校验，少一项直接 40001。
 * 本接口表达"确定了一套策略组合"，不是"逐类型增量改"；
 * 增量改走 {@link updateStrategyBinding}。
 *
 * @param bindings 三件套（预处理 / 切片 / 向量化）的类型与版本
 */
export async function updateStrategyBindings(
  knowledgeBaseId: string,
  bindings: StrategyBindItem[],
): Promise<boolean> {
  const response = await http.put<boolean>(`/knowledge-base/${knowledgeBaseId}/strategy-bindings`, {
    bindings,
  });
  return response.data;
}
