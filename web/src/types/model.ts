// 模型管理：模型目录与调用明细的类型与字典（与后端 VO / 字典逐字段对齐；厂商候选见 MODEL_VENDORS）

/** 模型状态码值（kb_model.status，字典 ENABLED / DISABLED） */
export const MODEL_STATUS = {
  ENABLED: 'ENABLED',
  DISABLED: 'DISABLED',
} as const;

/**
 * 能力码值：与后端 `ModelCapability` 枚举一致。
 *
 * <p>方案 §4.6 的能力字典另有 `RERANK` / `IMAGE` 两个取值，`common` 的枚举里没有，
 * 接口按枚举校验会拒绝这两个值，页面不列这两个取值。
 */
export const MODEL_CAPABILITIES = [
  'CHAT',
  'EMBEDDING',
  'OCR',
  'LAYOUT',
  'TABLE_STRUCTURE',
] as const;

/** 能力展示名（页面标签与下拉文案） */
export const MODEL_CAPABILITY_LABELS: Record<string, string> = {
  CHAT: '对话',
  EMBEDDING: '向量编码',
  OCR: '文字识别',
  LAYOUT: '版面分析',
  TABLE_STRUCTURE: '表格结构',
};

/** 币种码值（kb_model.currency） */
export const MODEL_CURRENCIES = ['CNY', 'USD'] as const;

/**
 * 厂商候选：码与后端配置 `knowledge.model.vendors` 的键一致，展示名与后端清单里的取名一致；
 * 新增厂商时同步该配置与这份枚举，两边都不在页面里增删。
 */
export const MODEL_VENDORS = [
  { code: 'dashscope', displayName: '阿里百炼' },
  { code: 'deepseek', displayName: 'DeepSeek' },
] as const;

/** 调用状态码值（后端 CallStatus） */
export const CALL_STATUS = {
  SUCCESS: 'SUCCESS',
  FAILED: 'FAILED',
  TIMEOUT: 'TIMEOUT',
  RATE_LIMITED: 'RATE_LIMITED',
  CIRCUIT_OPEN: 'CIRCUIT_OPEN',
} as const;

/** 调用状态展示名 */
export const CALL_STATUS_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
  RATE_LIMITED: '限流',
  CIRCUIT_OPEN: '熔断',
};

/** 路由原因码值（后端 RouteReason，无配额层与灰度，故无 BUDGET_EXCEEDED / SHADOW_ONLY） */
export const ROUTE_REASONS = [
  'OK',
  'CAPACITY_HIGH',
  'LATENCY_HIGH',
  'UNHEALTHY',
  'SLOW_START',
  'FALLBACK_FROM',
] as const;

/** 路由原因展示名 */
export const ROUTE_REASON_LABELS: Record<string, string> = {
  OK: '常规选型',
  CAPACITY_HIGH: '避开高占用',
  LATENCY_HIGH: '避开高延迟',
  UNHEALTHY: '避开不健康',
  SLOW_START: '恢复期爬升',
  FALLBACK_FROM: '降级切换',
};

/** 模型清单条目（对应后端 ModelItemVO） */
export interface ModelItem {
  id: string;
  vendorCode: string;
  vendorName: string;
  modelKey: string;
  displayName: string;
  capabilities: string[];
  contextWindow: number | null;
  priceInPerM: number | null;
  priceOutPerM: number | null;
  priceImagePerPage: number | null;
  pricePerThousandCalls: number | null;
  currency: string;
  rpmLimit: number | null;
  tpmLimit: number | null;
  status: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  remark: string | null;
}

/** 新增与编辑模型的请求体（对应后端 ModelSaveDto） */
export interface ModelSavePayload {
  vendorCode: string;
  modelKey: string;
  displayName: string | null;
  capabilities: string[];
  contextWindow: number | null;
  priceInPerM: number | null;
  priceOutPerM: number | null;
  priceImagePerPage: number | null;
  pricePerThousandCalls: number | null;
  currency: string;
  rpmLimit: number | null;
  tpmLimit: number | null;
  status: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  remark: string | null;
}

/** 模型清单的过滤条件 */
export interface ModelQuery {
  capability?: string;
  status?: string;
  keyword?: string;
}

/** 用量与成本查询条件：时间范围必填（from 含 / to 不含），其余维度可选 */
export interface ModelUsageQuery {
  from: string;
  to: string;
  capability?: string;
  model?: string;
  routeReason?: string;
  status?: string;
  limit?: number;
}

/** 调用明细一行（对应后端 ModelCallLogView；用量三项与 cost 为 null 表示未知，0 表示确实为零） */
export interface ModelCallLog {
  id: string;
  createTime: string;
  traceId: string | null;
  tenantId: number | null;
  bizType: string | null;
  bizId: string | null;
  capability: string | null;
  vendor: string | null;
  model: string | null;
  modelVersion: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  usageUnknown: number | null;
  unitPriceIn: number | null;
  unitPriceOut: number | null;
  currency: string | null;
  cost: number | null;
  latencyMs: number | null;
  retryCount: number | null;
  fallbackFrom: string | null;
  routeReason: string | null;
  cacheHit: number | null;
  status: string | null;
  errorCode: string | null;
}

/** 用量与成本概览（对应后端 ModelCallLogSummary；未知行不参与合计，无数据时聚合列为 null） */
export interface ModelCallLogSummary {
  calls: number | null;
  successCalls: number | null;
  failedCalls: number | null;
  timeoutCalls: number | null;
  rateLimitedCalls: number | null;
  unknownCalls: number | null;
  unknownRatio: number | null;
  totalTokens: number | null;
  costTotal: number | null;
}
