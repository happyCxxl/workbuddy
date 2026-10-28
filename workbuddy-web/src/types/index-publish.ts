/**
 * 索引的发布与校验类型（后端 `IndexValidateVO` / `BuildOrder` / `IndexBuildTriggerVO` 所在领域）。
 *
 * <p>与 `@/types/index-record` 的分界：那边是**版本记录本身**（有哪些版本、什么状态），
 * 这边是**动作与结果**（构建命令、校验结果）。页面两条路径各自 import，互不牵连。
 */
import { isBuilding, isSubsetScope } from '@/types/index-record';
import type { IndexFileScopeMode, IndexShape, IndexVersionVO } from '@/types/index-record';

/** 校验结果单项（后端 `IndexValidateItemVO`） */
export interface IndexValidateItemVO {
  /** CONSISTENCY / VECTOR_SMOKE / FULLTEXT_SMOKE */
  name: string;
  passed: boolean;
  detail: string;
}

/** 校验结果（后端 `IndexValidateVO`） */
export interface IndexValidateVO {
  /** 三项全过才为 true */
  passed: boolean;
  items: IndexValidateItemVO[];
}

/** 构建命令（后端 `BuildOrder`） */
export interface IndexBuildOrder {
  knowledgeBaseId: string;
  fileScopeMode: IndexFileScopeMode;
  /**
   * LIST 必填且非空；ALL 传 null。
   *
   * <p>后端 `buildCandidate` 对非 LIST 会强制归一化置空，前端先送 null 语义更清楚。
   */
  fileResultIds: string[] | null;
  /** 环节策略映射（绑定开启时可省略，由后端按绑定补齐） */
  stageStrategies: Record<string, string>;
  shape: IndexShape;
  /** 触发类型：手动构建用 REBUILD（不会自动发布） */
  trigger?: string;
}

/** 构建触发结果（后端 `IndexBuildTriggerVO`） */
export interface IndexBuildTriggerVO {
  versionId: string;
  versionNo: string;
  pipelineTaskId: string;
}

/** 校验项的中文名（后端只给英文枚举） */
export const VALIDATE_ITEM_LABELS: Record<string, string> = {
  CONSISTENCY: '产物与向量库对账',
  VECTOR_SMOKE: '向量检索冒烟',
  FULLTEXT_SMOKE: '全文检索冒烟',
};

export function validateItemLabel(name: string): string {
  return VALIDATE_ITEM_LABELS[name] ?? name;
}

/** 可发布：就绪、非在线、且不是子集 */
export function canPublish(version: IndexVersionVO): boolean {
  return version.status === 'READY' && !version.online && !isSubsetScope(version);
}

/** 可回退：已退役或就绪（后端要求当前必须已有一个在线版本），且不是子集 */
export function canRollback(version: IndexVersionVO): boolean {
  if (version.online || isSubsetScope(version) || isBuilding(version)) {
    return false;
  }
  return version.status === 'RETIRED' || version.status === 'READY';
}

/** 可删除：在线版本禁删（后端 40442） */
export function canDelete(version: Pick<IndexVersionVO, 'online' | 'status'>): boolean {
  return !version.online && !isBuilding(version);
}

/** 可校验：后端要求 READY / ONLINE / RETIRED（不满足时 40443） */
export function canValidate(version: Pick<IndexVersionVO, 'status'>): boolean {
  return version.status === 'READY' || version.status === 'ONLINE' || version.status === 'RETIRED';
}

/** 可重试：仅失败版本 */
export function canRetry(version: Pick<IndexVersionVO, 'status'>): boolean {
  return version.status === 'FAILED';
}
