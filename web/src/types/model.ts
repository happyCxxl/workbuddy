// 模型管理：模型目录与调用明细的类型与字典（与后端 VO / 字典逐字段对齐；厂商候选见 MODEL_VENDORS）

/** 模型状态码值（wb_model.status，字典 ENABLED / DISABLED） */
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
export const MODEL_CAPABILITIES = ['CHAT', 'EMBEDDING', 'OCR'] as const;

/** 能力展示名（页面标签与下拉文案） */
export const MODEL_CAPABILITY_LABELS: Record<string, string> = {
  CHAT: '对话',
  EMBEDDING: '向量编码',
  OCR: '文字识别',
};

/** 币种码值（wb_model.currency） */
export const MODEL_CURRENCIES = ['CNY', 'USD'] as const;

/**
 * 厂商候选：码与后端配置 `workbuddy.model.vendors` 的键一致，展示名与后端清单里的取名一致；
 * 新增厂商时同步该配置与这份枚举，两边都不在页面里增删。
 */
export const MODEL_VENDORS = [
  { code: 'dashscope', displayName: '阿里百炼' },
  { code: 'deepseek', displayName: 'DeepSeek' },
] as const;

/** 模型清单条目（对应后端 ModelItemVO） */
export interface ModelItem {
  id: string;
  vendorCode: string;
  vendorName: string;
  modelKey: string;
  capabilities: string[];
  thinkingEnabled: number;
  inputLimit: number | null;
  outputLimit: number | null;
  thinkingInputLimit: number | null;
  thinkingOutputLimit: number | null;
  contextWindow: number | null;
  priceInPerM: number | null;
  priceInPerMCacheHit: number | null;
  priceOutPerM: number | null;
  currency: string;
  rpmLimit: number | null;
  tpmLimit: number | null;
  status: string;
}

/** 新增与编辑模型的请求体（对应后端 ModelSaveDto；不含状态，停用走启停接口） */
export interface ModelSavePayload {
  vendorCode: string;
  modelKey: string;
  capabilities: string[];
  thinkingEnabled: number;
  inputLimit: number | null;
  outputLimit: number | null;
  thinkingInputLimit: number | null;
  thinkingOutputLimit: number | null;
  contextWindow: number | null;
  priceInPerM: number | null;
  priceInPerMCacheHit: number | null;
  priceOutPerM: number | null;
  currency: string;
  rpmLimit: number | null;
  tpmLimit: number | null;
}

/** 模型清单的分页查询条件（页码与页长可空，按后端默认 1 / 10 兜底） */
export interface ModelPageQuery {
  current?: number;
  size?: number;
  capability?: string;
  status?: string;
  keyword?: string;
}
