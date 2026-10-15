/**
 * 首页的类型：资产速览 + 最近提交（仅本人）。
 *
 * <p>最近提交数据源是 `wb_submit_log`，`status` 是**提交校验结果** PASS/FAIL（不是处理链进度）；
 * 可见范围由后端按当前登录用户限定，前端不传提交人。
 */

/** 资产速览（后端 `HomeSummaryVO`）：每类都给细分，避免为看细分再点进对应页 */
export interface HomeSummary {
  knowledgeBaseCount: number;
  enabledKnowledgeBaseCount: number;
  /** 可用策略版本数（四类合计，**只含启用中的**） */
  strategyVersionCount: number;
  preprocessVersionCount: number;
  chunkVersionCount: number;
  embedVersionCount: number;
  retrievalVersionCount: number;
  /** 已建档文档数（`wb_file_result` 行数；**文件校验失败的提交不建结果**，不计入） */
  documentCount: number;
}

/** 最近提交一行（后端 `HomeRecentSubmitVO`） */
export interface HomeRecentSubmit {
  id: string;
  /** 文件名（文件不存在等场景可能为空串） */
  fileName: string;
  /** 文件类型（后端由来源文件的 MIME 换算：PDF / DOCX / XLSX …）；取不到时为 null */
  fileType: string | null;
  knowledgeBaseId: string;
  /** 所属知识库名（后端反查；已删库回落成「知识库 {ID}」） */
  knowledgeBaseName: string;
  /** 提交结果：PASS / FAIL */
  status: string;
  /** 失败原因码值（PASS 时为空） */
  failReason: string | null;
  /** 失败原因中文名（后端下发） */
  failReasonLabel: string | null;
  createTime: string;
}

/** 最近提交的查询条件 */
export interface HomeRecentSubmitQuery {
  current: number;
  size: number;
}

/**
 * 提交时间展示：`yyyy-MM-dd HH:mm`。
 *
 * <p>带年份：这张表是本人提交历史的唯一入口，跨年时 `MM-DD` 分不清是哪一年。
 */
export function formatSubmitTime(value: string | null): string {
  if (!value) {
    return '—';
  }
  const normalized = value.replace('T', ' ');
  return normalized.length >= 16 ? normalized.slice(0, 16) : normalized;
}
