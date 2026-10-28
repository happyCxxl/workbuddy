import { http } from './http';
import type { HomeRecentSubmit, HomeRecentSubmitQuery, HomeSummary } from '@/types/home';
import type { PageResult } from '@/types/response';

/**
 * 首页接口。
 *
 * <p>两个接口对应首页的两块内容：**有多少资产**、**我最近提交了什么**。
 * 只有这两块，没有"环节分布""待处理清单"这类聚合；管理员动作（审计）也不在首页展示。
 */

/** 资产速览：知识库 / 可用策略版本（四类细分）/ 已建档文档数 */
export async function getHomeSummary(): Promise<HomeSummary> {
  const response = await http.get<HomeSummary>('/home/summary');
  return response.data;
}

/**
 * 最近提交：**当前登录用户**的文件提交记录（新→旧）。
 *
 * <p>`status` 是**提交校验结果**（PASS/FAIL），不是处理链进度；
 * 可见范围由后端按登录用户限定，前端不传提交人。
 */
export async function getHomeRecentSubmits(
  query: HomeRecentSubmitQuery,
): Promise<PageResult<HomeRecentSubmit>> {
  const response = await http.get<PageResult<HomeRecentSubmit>>('/home/recent-submits', {
    params: query,
  });
  return response.data;
}
