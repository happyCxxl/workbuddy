/** 提交结果状态：后端 wb_submit_log.status */
export type SubmitStatus = 'PASS' | 'FAIL';

/** 提交日志（后端 SubmitLogVO）：提交记录列表与提交响应的数据源 */
export interface SubmitLog {
  id: string;
  knowledgeBaseId: string;
  /** 幂等键（回显，凭此识别同一请求） */
  requestId: string;
  fileId: string;
  sha256: string;
  fileName: string;
  /** 文件结果 ID（失败时为空） */
  fileResultId: string | null;
  /** 提交结果 */
  status: string;
  /** 失败原因（PASS 时为空） */
  failReason: string | null;
  createTime: string | null;
}

/**
 * 提交响应（后端 FileSubmitVO）。
 *
 * <p>手动逐环节口径：首次提交时 `pipelineTaskId` 为空，解析要另行触发。
 */
export interface FileSubmitResult {
  submitLog: SubmitLog;
  pipelineTaskId: string | null;
}

/** 提交记录分页入参 */
export interface SubmitLogQuery {
  current: number;
  size: number;
  /** 提交结果筛选：PASS / FAIL */
  status?: string;
  /** 文件名模糊筛选 */
  fileName?: string;
}

/** 后端允许的文件格式（knowledge.file.enabled-formats 默认值，识别依据是 Tika 魔数而非扩展名） */
export const ACCEPTED_FILE_EXTENSIONS = ['.pdf', '.doc', '.docx', '.xls', '.xlsx'] as const;

/** 单文件大小上限（后端 knowledge.file.max-size = 104857600） */
export const MAX_UPLOAD_BYTES = 104857600;

/** 文件选择框的 accept 值 */
export const FILE_ACCEPT_ATTR = ACCEPTED_FILE_EXTENSIONS.join(',');

/**
 * 前端预检文件是否可能被接受。
 *
 * <p>只做**提示性**拦截（少一次白跑的上传），最终判定仍在后端：
 * 后端按 Tika 魔数识别真实格式，不信任扩展名，扩展名合法也可能被拒。
 *
 * @returns 不通过时返回原因文案，通过返回 null
 */
export function checkFileBeforeUpload(file: File): string | null {
  const name = file.name.toLowerCase();
  if (!ACCEPTED_FILE_EXTENSIONS.some((ext) => name.endsWith(ext))) {
    return `不支持的格式，仅接受 ${ACCEPTED_FILE_EXTENSIONS.join(' / ')}`;
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return `超过单文件上限 ${formatBytes(MAX_UPLOAD_BYTES)}`;
  }
  if (file.size === 0) {
    return '文件为空';
  }
  return null;
}

/** 字节数转可读大小 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes}B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(0)}KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}
