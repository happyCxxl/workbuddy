import { http } from './http';
import type { PageResult } from '@/types/response';
import type {
  KnowledgeBase,
  KnowledgeBaseCreateRequest,
  KnowledgeBasePageQuery,
  KnowledgeBaseStats,
  KnowledgeBaseUpdateRequest,
} from '@/types/knowledge-base';

/** 分页查询知识库（支持名称模糊与状态过滤，不含已删除；每条含文档数） */
export async function getKnowledgeBasePage(
  query: KnowledgeBasePageQuery,
): Promise<PageResult<KnowledgeBase>> {
  const response = await http.get<PageResult<KnowledgeBase>>('/knowledge-base/page', {
    params: query,
  });
  return response.data;
}

/** 查询知识库统计概览（总数、启用数、文档总数） */
export async function getKnowledgeBaseStats(): Promise<KnowledgeBaseStats> {
  const response = await http.get<KnowledgeBaseStats>('/knowledge-base/stats');
  return response.data;
}

/** 查询知识库详情（编辑前取最新值，避免用列表的陈旧快照覆盖） */
export async function getKnowledgeBaseDetail(id: string): Promise<KnowledgeBase> {
  const response = await http.get<KnowledgeBase>(`/knowledge-base/${id}`);
  return response.data;
}

/** 创建知识库（一律落普通库并默认启用），返回新建 ID */
export async function addKnowledgeBase(request: KnowledgeBaseCreateRequest): Promise<string> {
  const response = await http.post<string>('/knowledge-base/create', request);
  return response.data;
}

/** 更新知识库（仅名称/说明/策略绑定开关；状态与策略绑定有独立接口） */
export async function updateKnowledgeBase(
  id: string,
  request: KnowledgeBaseUpdateRequest,
): Promise<void> {
  await http.put<void>(`/knowledge-base/${id}`, request);
}

/**
 * 停用知识库（`POST /knowledge-base/{id}/disable`）。
 *
 * <p>停用是「中止服务」：该库随后拒绝新文件接入与检索（后端 `checkCanSubmit` → 40421）。
 *
 * <p>命名沿用策略版本那两个的写法（`updateStrategyVersionDisable`）：动词只能取
 * 规范 §3.2 词表里的 add/update/delete/get/detail，enable/disable 归一到 update。
 */
export async function updateKnowledgeBaseDisable(id: string): Promise<void> {
  await http.post<void>(`/knowledge-base/${id}/disable`);
}

/** 启用知识库（`POST /knowledge-base/{id}/enable`；仅停用状态可启用，不满足时 40402） */
export async function updateKnowledgeBaseEnable(id: string): Promise<void> {
  await http.post<void>(`/knowledge-base/${id}/enable`);
}

/** 删除知识库（逻辑删除，无恢复接口） */
export async function deleteKnowledgeBase(id: string): Promise<void> {
  await http.delete<void>(`/knowledge-base/${id}`);
}
