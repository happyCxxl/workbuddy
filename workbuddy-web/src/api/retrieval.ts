import { http } from './http';
import type {
  RetrievalRulePublishResult,
  RetrievalRun,
  SearchRequest,
  SearchResult,
} from '@/types/retrieval';

/**
 * 生产检索：不传 ruleId / versionId，走回退链（在线版本行 → kb 默认 → 引擎基线）。
 *
 * <p>与测试台是**同一个执行引擎**，区别只在"规则如何确定"。
 * 未发布索引时后端报 40446。
 */
export async function getSearchResult(
  knowledgeBaseId: string,
  request: SearchRequest,
): Promise<SearchResult> {
  const response = await http.post<SearchResult>(
    `/knowledge-base/${knowledgeBaseId}/search`,
    request,
  );
  return response.data;
}

/**
 * 测试台检索：**必须显式传 versionId + ruleId**（可检索候选冻结集）。
 *
 * <p>执行即落运行记录（`wb_knowledge_retrieval_run`，append-only 机器表），
 * 后续的勾选对比就回放这些记录里的快照 —— **不重跑**。
 * 这是评测页的主路径。
 */
export async function addRetrievalTest(
  knowledgeBaseId: string,
  request: SearchRequest,
): Promise<SearchResult> {
  const response = await http.post<SearchResult>(
    `/knowledge-base/${knowledgeBaseId}/retrieval-test`,
    request,
  );
  return response.data;
}

/**
 * 检索运行记录列表（新→旧）。
 *
 * @param limit 截断条数；≤0 表示全部
 */
export async function getRetrievalRuns(
  knowledgeBaseId: string,
  limit = 50,
): Promise<RetrievalRun[]> {
  const response = await http.get<RetrievalRun[]>(
    `/knowledge-base/${knowledgeBaseId}/retrieval-runs`,
    {
      params: { limit },
    },
  );
  return response.data;
}

/**
 * 勾选对比回放：按运行记录 ID 并排返回**执行时刻的快照**。
 *
 * <p>**不重跑**（快照即证据）—— 索引集合是 append-only 的，重跑得到的候选集与记录不一致。
 *
 * @param runIds 按此顺序并排
 */
export async function addRetrievalRunsCompare(
  knowledgeBaseId: string,
  runIds: string[],
): Promise<SearchResult[]> {
  const response = await http.post<SearchResult[]>(
    `/knowledge-base/${knowledgeBaseId}/retrieval-runs/compare`,
    { runIds },
  );
  return response.data;
}

/**
 * 规则选优发布：把规则行设为**指定索引版本行**的默认规则。
 *
 * <p>发布索引与发布规则是**两个独立动作** —— 换规则不需要重建索引集合。
 */
export async function updateRetrievalRulePublish(
  knowledgeBaseId: string,
  versionId: string,
  ruleId: string,
): Promise<RetrievalRulePublishResult> {
  const response = await http.post<RetrievalRulePublishResult>(
    `/knowledge-base/${knowledgeBaseId}/retrieval-rules/publish`,
    { versionId, ruleId },
  );
  return response.data;
}
