/** 策略版本状态 */
export type StrategyStatus = 'ACTIVE' | 'INACTIVE';

/**
 * 策略版本（后端 StrategyVersionVO）：前端策略选择下拉的数据源。
 *
 * <p>注意 RPC 字段口径：`strategyName` + `strategyVersion` 是两个独立字段，
 * 而执行树节点上的 `strategyVersion` 是 `name-version` 合成串，比对时需自行拼接。
 */
export interface StrategyVersion {
  /** 策略版本行 ID（雪花 ID，字符串传输） */
  id: string;
  type: string;
  /** 策略名（如 preproc-default） */
  name: string;
  /** 版本号（如 v1） */
  version: string;
  /** 配置快照 JSON 文本（完整参数与规则开关） */
  configSnapshot: string | null;
  status: string;
  createTime: string | null;
}

/** 策略版本的展示名：name-version */
export function strategyDisplayName(item: StrategyVersion): string {
  return `${item.name}-${item.version}`;
}
