import { http } from './http';
import type { PageResult } from '@/types/response';
import type {
  ChunkDetail,
  FileResult,
  Lineage,
  ParseDetail,
  PreprocessDetail,
  StageContentPage,
  StageTriggerResult,
  StrategyBinding,
  StructureDetail,
} from '@/types/pipeline';

/** 分页查询知识库下的文件结果（每行含各环节最新任务状态） */
export async function getFileResults(
  knowledgeBaseId: string,
  query: { current: number; size: number; stage?: string },
): Promise<PageResult<FileResult>> {
  const response = await http.get<PageResult<FileResult>>(
    `/knowledge-base/${knowledgeBaseId}/file-results`,
    { params: query },
  );
  return response.data;
}

/** 查询某个文件结果的执行树（全部环节节点 + 血缘边） */
export async function getLineage(fileResultId: string): Promise<Lineage> {
  const response = await http.get<Lineage>(`/file-results/${fileResultId}/lineage`);
  return response.data;
}

/**
 * 查询解析详情（任务状态 + 子步骤 + 告警 + 统计）。
 *
 * <p>`taskId` 不传取该环节最新任务；传了则看该次历史运行 —— 详情抽屉是从某张卡片打开的，
 * 必须带上那张卡的 taskId，否则会显示成"最新一次"而不是"这一张"。
 */
export async function getParseDetail(fileResultId: string, taskId?: string): Promise<ParseDetail> {
  const response = await http.get<ParseDetail>(`/file-results/${fileResultId}/parse-detail`, {
    params: taskId ? { taskId } : {},
  });
  return response.data;
}

/**
 * 按环节取详情：每个环节有自己的详情路径，各自返回专属字段。
 *
 * <p>解析走 `parse-detail`（统计结构固定），组装走 `structure-detail`（含大纲与冲突），
 * 预处理走 `preprocess-detail`（含派生视图元素与清洗统计），
 * 切片走 `chunk-detail`（含切片列表与切片统计）。
 * 未接详情接口的环节返回 `null`，调用侧按"没有详情"渲染 —— 不回落成别的环节的接口。
 *
 * <p>`taskId` 同上：必须传被查看的那次运行。
 */
export async function getStageDetail(
  stage: string,
  fileResultId: string,
  taskId?: string,
): Promise<ParseDetail | StructureDetail | PreprocessDetail | ChunkDetail | null> {
  if (stage === 'PARSE') {
    return getParseDetail(fileResultId, taskId);
  }
  const params = taskId ? { taskId } : {};
  if (stage === 'STRUCTURE') {
    const response = await http.get<StructureDetail>(
      `/file-results/${fileResultId}/structure-detail`,
      { params },
    );
    return response.data;
  }
  if (stage === 'PREPROCESS') {
    const response = await http.get<PreprocessDetail>(
      `/file-results/${fileResultId}/preprocess-detail`,
      { params },
    );
    return response.data;
  }
  if (stage === 'CHUNK') {
    const response = await http.get<ChunkDetail>(`/file-results/${fileResultId}/chunk-detail`, {
      params,
    });
    return response.data;
  }
  return null;
}

/**
 * 取某个文件对象的原始字节（原文件下载用：`GET /files/{fileId}`）。
 *
 * <p>走统一实例的 blob 档：令牌由请求拦截器加在请求头里，因此**不能直接用普通链接打开**该地址
 * ——JWT 过滤器只认 `Authorization` 头，不认查询参数里的令牌。
 *
 * <p>该接口直出字节流、没有统一响应体：必须带 {@link rawResponse}，
 * 否则响应拦截器会把 Blob 当信封解包，每次都被判成失败。
 */
export async function getSourceFile(fileId: string): Promise<Blob> {
  const response = await http.get<Blob>(`/files/${fileId}`, {
    responseType: 'blob',
    transformResponse: [(data: unknown) => data],
    rawResponse: true,
  });
  return response.data;
}

/**
 * 分页查询某环节某次运行的产物内容。
 *
 * <p>`taskId` 同上，必须传被查看的那次运行；`current` 从 1 起，`size` 由页面按行数档位给。
 * `docPage` 按文档页收窄（只回该页元素），原文预览与解析结果按页联动用；
 * `status` 按处置状态收窄（逗号分隔，只回这些状态的元素），预处理环节核对剔除内容用；
 * `contentType` / `fallback` / `hasParent` 收窄切片内容（只对 CHUNK 生效，过滤在 DB 侧做）。
 */
export async function getStageContent(
  fileResultId: string,
  stage: string,
  query: {
    taskId?: string;
    docPage?: number;
    status?: string;
    contentType?: string;
    fallback?: boolean;
    hasParent?: boolean;
    current?: number;
    size?: number;
  } = {},
): Promise<StageContentPage> {
  const params: Record<string, string | number | boolean> = { stage };
  if (query.taskId) {
    params.taskId = query.taskId;
  }
  if (query.docPage) {
    params.docPage = query.docPage;
  }
  if (query.status) {
    params.status = query.status;
  }
  if (query.contentType) {
    params.contentType = query.contentType;
  }
  if (query.fallback !== undefined) {
    params.fallback = query.fallback;
  }
  if (query.hasParent !== undefined) {
    params.hasParent = query.hasParent;
  }
  if (query.current) {
    params.current = query.current;
  }
  if (query.size) {
    params.size = query.size;
  }
  const response = await http.get<StageContentPage>(`/file-results/${fileResultId}/stage-content`, {
    params,
  });
  return response.data;
}

/** 查询知识库某类策略的当前绑定（未绑定返回仅含 strategyType 的空对象） */
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
 * 触发某个环节执行（手动逐环节）。
 *
 * <p>语义：`upstreamProductId` 指定「以此产物触发下游」，实现分叉——传哪个上游产物，
 * 就基于它产出一条新的分支。不传则由后端按知识库绑定策略 + 同策略最新成功运行解析。
 * 解析环节无上游产物，故不接受该参数。
 */
export async function addStageTrigger(
  fileResultId: string,
  stage: string,
  options: { strategyVersionId?: string | null; upstreamProductId?: string | null } = {},
): Promise<StageTriggerResult> {
  // 解析环节的接口不接受任何可选参数，多余参数会被后端忽略，这里按需要传
  const params: Record<string, string> = {};
  if (options.strategyVersionId) {
    params.strategyVersionId = options.strategyVersionId;
  }
  if (options.upstreamProductId) {
    params.upstreamProductId = options.upstreamProductId;
  }
  const path = `/file-results/${fileResultId}/${stage.toLowerCase()}`;
  const response = await http.post<StageTriggerResult>(path, null, { params });
  return response.data;
}
