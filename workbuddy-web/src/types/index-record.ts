/**
 * 索引版本行的类型与状态口径（后端 `IndexVersionVO` 所在领域）。
 *
 * <p>发布与校验相关的类型在 `@/types/index-publish`：两者是不同领域
 * （版本记录 vs 发布运行态）。
 */

/**
 * 索引版本状态机（与后端 `IndexVersionStatus` 一致）。
 *
 * <pre>
 * CREATED → BUILDING → READY →（发布）ONLINE
 * ONLINE →（新组合上线 / 回退切走）RETIRED
 * RETIRED →（回退复活）ONLINE
 * 任意非终态可 FAILED；回收 = 物理删除 + drop 集合
 * </pre>
 */
export const INDEX_VERSION_STATUSES = [
  'CREATED',
  'BUILDING',
  'READY',
  'ONLINE',
  'FAILED',
  'RETIRED',
] as const;

export type IndexVersionStatus = (typeof INDEX_VERSION_STATUSES)[number];

/** 状态中文名 */
export const INDEX_STATUS_LABELS: Record<string, string> = {
  CREATED: '已创建',
  BUILDING: '构建中',
  READY: '就绪待发布',
  ONLINE: '在线',
  FAILED: '构建失败',
  RETIRED: '已退役',
};

/** 状态语气（决定徽标配色，与 pipeline 的 tone 口径一致） */
export type IndexStatusTone = 'online' | 'ready' | 'building' | 'failed' | 'retired';

const STATUS_TONES: Record<string, IndexStatusTone> = {
  CREATED: 'building',
  BUILDING: 'building',
  READY: 'ready',
  ONLINE: 'online',
  FAILED: 'failed',
  RETIRED: 'retired',
};

export function indexStatusTone(status: string): IndexStatusTone {
  return STATUS_TONES[status] ?? 'retired';
}

export function indexStatusLabel(status: string): string {
  return INDEX_STATUS_LABELS[status] ?? status;
}

/** 文件范围模式：ALL 全库 / LIST 指定文件（子集） */
export type IndexFileScopeMode = 'ALL' | 'LIST';

/** 索引形态：当前只有「全文 + 向量」 */
export type IndexShape = 'FULL_VECTOR' | string;

/**
 * 索引版本行（后端 `IndexVersionVO`）。
 *
 * <p>**`chunkCount` / `vectorCount` 是「活账本」**：构建期在 READY 时一次性收敛，
 * 但在线后随新文件追加持续增长 —— 它们会自己变大，不是快照。
 */
export interface IndexVersionVO {
  id: string;
  /** 版本号（v1、v2…），也是集合名 `kb_{kbId}_{versionNo}` 的组成部分 */
  versionNo: string;
  fileScopeMode: IndexFileScopeMode;
  /** LIST 范围的成员文件 ID；ALL 为空 */
  fileResultIds: string[] | null;
  /** 环节策略映射：stage → "策略名-版本" */
  stageStrategies: Record<string, string> | null;
  /** 切片策略 name-version */
  chunkStrategy: string | null;
  /** 向量化策略 name-version */
  embedStrategy: string | null;
  shape: IndexShape | null;
  /** 纳入片数（活账本） */
  chunkCount: number;
  /** 纳入向量数（活账本） */
  vectorCount: number;
  status: IndexVersionStatus | string;
  /** 失败原因（后端只给一个字符串，不含结构化缺口） */
  buildError: string | null;
  /** 构建任务 ID（wb_knowledge_pipeline_task，stage=BUILD_INDEX） */
  taskId: string | null;
  /** 最近一次全量对账时间（追加后也会更新，可当「最近追加」看） */
  validatedAt: string | null;
  publishedAt: string | null;
  publishedBy: string | null;
  retiredAt: string | null;
  retiredBy: string | null;
  createTime: string;
  /** 是否当前在线版本（后端按指针比对给出） */
  online: boolean;
}

/** 可构建组合（后端 `IndexComboVO`） */
export interface IndexComboVO {
  fileScopeMode: IndexFileScopeMode;
  /** 该组合血统匹配的成员文件（范围收窄时只含范围内文件） */
  fileResultIds: string[] | null;
  stageStrategies: Record<string, string> | null;
  chunkStrategy: string | null;
  embedStrategy: string | null;
  shape: IndexShape | null;
  /** 产物完整标记（枚举只返回完整组合，恒 true） */
  complete: boolean;
  /** 成员文件数 */
  fileCount: number;
  /** 构建前预览向量数（Σ 成员最新向量集合 recordCount） */
  vectorCount: number;
}

/** 是否是子集（指定文件）版本 —— 子集不可发布/回退，这是业务规则 */
export function isSubsetScope(version: Pick<IndexVersionVO, 'fileScopeMode'>): boolean {
  return version.fileScopeMode === 'LIST';
}

/** 范围的中文描述：`全库` / `指定 N 个文件` */
export function scopeText(
  version: Pick<IndexVersionVO, 'fileScopeMode' | 'fileResultIds'>,
): string {
  if (version.fileScopeMode !== 'LIST') {
    return '全库';
  }
  const count = version.fileResultIds?.length ?? 0;
  return count > 0 ? `指定 ${count} 个文件` : '指定文件';
}

/** 构建中（含 CREATED）：此时禁止一切操作（后端 40443） */
export function isBuilding(version: Pick<IndexVersionVO, 'status'>): boolean {
  return version.status === 'CREATED' || version.status === 'BUILDING';
}

/** 时间展示：后端给 ISO 串，这里截到分钟 */
export function formatIndexTime(value: string | null): string {
  if (!value) {
    return '—';
  }
  return value.replace('T', ' ').slice(0, 16);
}

/** 策略三元组按环节固定顺序展示（后端 stageStrategies 的顺序不保证） */
export const COMBO_STAGE_ORDER = ['PREPROCESS', 'CHUNK', 'EMBED'] as const;

/** 环节中文名 */
export const COMBO_STAGE_LABELS: Record<string, string> = {
  PREPROCESS: '预处理',
  CHUNK: '切片',
  EMBED: '向量化',
};

/**
 * 组合标识：把 stageStrategies 拼成稳定的可比较字符串。
 *
 * <p>用于「同一条组合」的分组 —— 后端没有暴露组合 ID，
 * 血缘靠 stageStrategies 的映射表达，按它分组。
 */
export function comboKeyOf(stageStrategies: Record<string, string> | null): string {
  if (!stageStrategies) {
    return '';
  }
  return COMBO_STAGE_ORDER.map((stage) => `${stage}=${stageStrategies[stage] ?? ''}`).join('|');
}

/** 组合可读拆分：按固定环节顺序给出「环节名 + 策略值」 */
export function comboParts(
  stageStrategies: Record<string, string> | null,
): { stage: string; label: string; value: string }[] {
  if (!stageStrategies) {
    return [];
  }
  return COMBO_STAGE_ORDER.filter((stage) => stageStrategies[stage]).map((stage) => ({
    stage,
    label: COMBO_STAGE_LABELS[stage] ?? stage,
    value: stageStrategies[stage],
  }));
}
