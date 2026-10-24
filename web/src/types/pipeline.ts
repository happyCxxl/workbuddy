/** 处理链环节（与后端 PipelineStage 枚举对齐；BUILD_INDEX 及之后属另外的视图） */
export const PIPELINE_STAGES = ['PARSE', 'STRUCTURE', 'PREPROCESS', 'CHUNK', 'EMBED'] as const;

export type PipelineStage = (typeof PIPELINE_STAGES)[number];

/**
 * 环节中文名。
 *
 * <p>用索引签名而非 Record&lt;PipelineStage, string&gt;：后端可能返回本端尚未枚举的环节，
 * 断言类型会让 TS 以为查表必定命中，实际会渲染出 undefined。
 */
export const STAGE_LABELS: Record<string, string> = {
  PARSE: '解析',
  STRUCTURE: '组装',
  PREPROCESS: '预处理',
  CHUNK: '切片',
  EMBED: '向量化',
};

/** 取环节展示名；未知环节回落原值，避免渲染出 undefined */
export function stageLabel(stage: string): string {
  return STAGE_LABELS[stage] ?? stage;
}

/**
 * 解析元素类型 → 展示名（解析详情抽屉的元素列表用）。
 *
 * <p>同样用索引签名：产物可能含本端未枚举的类型，未知类型回落原值。
 */
export const ELEMENT_TYPE_LABELS: Record<string, string> = {
  PARAGRAPH: '段落',
  TABLE: '表格',
  TABLE_CELL: '单元格',
  IMAGE: '图片',
  FIGURE_CAPTION: '图注',
  HEADER: '页眉',
  FOOTER: '页脚',
  TITLE: '标题',
  LIST: '列表',
};

/** 取元素类型展示名；未知类型回落原值 */
export function elementTypeLabel(type: string | null | undefined): string {
  if (!type) {
    return '—';
  }
  return ELEMENT_TYPE_LABELS[type] ?? type;
}

/**
 * 切片内容类型展示名（`ChunkContentType` 枚举名 → 片的中文名）。
 *
 * <p>与元素类型分开：切片的 SECTION 是"父片"，与元素的标题 / 章节不是同一层概念。
 */
export const CHUNK_TYPE_LABELS: Record<string, string> = {
  SECTION: '父片',
  PARAGRAPH: '正文',
  TABLE: '表格',
  IMAGE: '图片',
  FALLBACK: '兜底',
};

/** 取切片类型展示名；未知类型回落原值 */
export function chunkTypeLabel(type: string | null | undefined): string {
  if (!type) {
    return '—';
  }
  return CHUNK_TYPE_LABELS[type] ?? type;
}

/** 任务状态（与后端 PipelineTaskStatus 枚举对齐） */
export type PipelineTaskStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'PARTIAL_SUCCESS' | 'FAILED';

/** 任务状态中文名（同样用索引签名，未知状态回落原值） */
export const TASK_STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  PARTIAL_SUCCESS: '部分成功',
  FAILED: '失败',
};

/** 取任务状态展示名；未知状态回落原值 */
export function taskStatusLabel(status: string): string {
  return TASK_STATUS_LABELS[status] ?? status;
}

/**
 * 任务终态：进入这些状态后不会再变化，轮询即可停止。
 *
 * <p>用白名单而非「是否 RUNNING」判断：任务可能长期处于 QUEUED，也可能直接进入
 * PARTIAL_SUCCESS / FAILED；只排除 RUNNING 会漏判（轮询永不停止）。
 */
export const TERMINAL_TASK_STATUSES = new Set<string>(['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED']);

/** 任务是否仍在推进（未进入终态） */
export function isTaskPending(status: string | null | undefined): boolean {
  if (!status) {
    // 状态未知时按未完成处理，给后端回填状态留时间
    return true;
  }
  return !TERMINAL_TASK_STATUSES.has(status);
}

/** 状态视觉归类：ok 成功 / partial 部分成功 / run 进行中 / wait 排队 / fail 失败 */
export type StatusTone = 'ok' | 'partial' | 'run' | 'wait' | 'fail';

export function statusTone(status: string | null | undefined): StatusTone {
  switch (status) {
    case 'SUCCESS':
      return 'ok';
    case 'PARTIAL_SUCCESS':
      return 'partial';
    case 'RUNNING':
      return 'run';
    case 'FAILED':
      return 'fail';
    default:
      return 'wait';
  }
}

/** 文件某项环节的最新任务状态（后端 StageStatusVO；无任务的环节不返回该条目） */
export interface StageStatus {
  stage: string;
  taskId: string | null;
  status: string | null;
  errorCode: string | null;
  errorMsg: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

/** 文件结果（后端 FileResultVO）：执行链列表数据源 */
export interface FileResult {
  id: string;
  knowledgeBaseId: string;
  sourceFileId: string;
  fileId: string;
  fileName: string;
  fileSize: number | null;
  createTime: string | null;
  /**
   * 各环节最新任务状态（无任务的环节不出现）。
   *
   * <p>**可空**：全新导入、尚未跑过任何环节的文件，后端返回 null 而不是空数组
   * （后端会给 `stageStatuses: null`）。取值一律走 {@link stageStatusList}，直接当数组用会抛异常。
   */
  stageStatuses: StageStatus[] | null;
  /**
   * 这条结果的**原始文件**所在的存储类型码值（如 `minio` / `local`）。
   *
   * <p>**可空**：存储类型尚未下发时不显示徽标。徽标标签用它，能不能继续执行由
   * {@link storageSourceId} 决定。
   */
  storageType: string | null;
  /**
   * **当前写入后端**的存储类型码值。
   *
   * <p>同一次响应里每一条记录都给同一个值；展示名回落时用它。
   */
  currentStorageType: string | null;
  /**
   * 这条结果的**原始文件**所在的数据源 ID。
   *
   * <p>**可空**：数据源尚未下发时按取不到判据处理，不显示徽标也不拦触发。
   */
  storageSourceId: string | null;
  /**
   * 这条结果的**原始文件**所在数据源的显示名。
   *
   * <p>**可空**：数据源未注册（已删）时后端给空，取值走 {@link fileStorageLabel} 回落。
   */
  storageSourceName: string | null;
  /**
   * **当前启用数据源**的 ID。
   *
   * <p>同一次响应里每一条记录都给同一个值；与 {@link storageSourceId} 不一致表示这条链的
   * 产物落在别的数据源上，下游环节不能再往下走。
   */
  currentStorageSourceId: string | null;
  /**
   * **当前启用数据源**的显示名。
   *
   * <p>**可空**：没有启用的数据源或该数据源未注册时后端给空，取值走 {@link fileStorageLabel} 回落。
   */
  currentStorageSourceName: string | null;
}

/** 安全取环节状态列表：后端可能返回 null（新导入文件），统一按空数组处理 */
export function stageStatusList(file: FileResult): StageStatus[] {
  return file.stageStatuses ?? [];
}

/**
 * 存储类型码值 → 展示名。
 *
 * <p>用索引签名而非枚举联合：存储后端后续会有别的码值，未知码值原样显示比显示空白有用。
 */
export const FILE_STORAGE_LABELS: Record<string, string> = {
  minio: 'MinIO',
  local: '本地',
};

/** 取存储类型展示名；未知码值回落原值 */
export function fileStorageName(storageType: string): string {
  return FILE_STORAGE_LABELS[storageType] ?? storageType;
}

/**
 * 取存储展示名：数据源显示名优先，取不到时回落该结果的存储类型展示名。
 *
 * @param storageSourceName 数据源显示名（后端未下发时为 null）
 * @param storageType 存储类型码值（后端未下发时为 null）
 * @return 展示名；两侧都取不到时返回 `—`
 */
export function fileStorageLabel(
  storageSourceName: string | null,
  storageType: string | null,
): string {
  if (storageSourceName) {
    return storageSourceName;
  }
  return storageType ? fileStorageName(storageType) : '—';
}

/** 这条结果的原始文件是否落在当前启用的数据源上；两侧任一缺 ID 时按「取不到判据」处理 */
export function sameStorageSource(file: FileResult): boolean {
  return !file.storageSourceId || !file.currentStorageSourceId
    ? true
    : file.storageSourceId === file.currentStorageSourceId;
}

/**
 * 这条结果不能继续执行的原因文案；可以继续（或数据源 ID 未下发）时为空串。
 *
 * <p>判据是**数据源实例 ID**：同类型的两个实例（如两台 MinIO）之间切换同样是不可继续的状态，
 * 类型码值相同不代表产物落在同一个后端上。两侧任一缺 ID 时判为可以继续，
 * 与后端「档案没记数据源 ID 就放行」的口径一致。文案与后端拒绝执行时给的运行时消息同款。
 */
export function storageBlockReason(file: FileResult | null | undefined): string {
  if (!file || sameStorageSource(file)) {
    return '';
  }
  const archive = fileStorageLabel(file.storageSourceName, file.storageType);
  const current = fileStorageLabel(file.currentStorageSourceName, file.currentStorageType);
  return `该任务属于 ${archive}，当前启用的是 ${current}，无法继续执行；把当前启用切回 ${archive} 后可继续`;
}

/**
 * 能力快照（后端 LineageCapabilityVO）。
 *
 * <p>**是对象而不是 JSON 串**：后端已把 product.capabilitySnapshot 的 JSON 文本解析开，
 * 前端不需要（也不应该）自己解析。
 */
export interface LineageCapability {
  /** 原生解析器名称（如 pdfbox） */
  parserName: string | null;
  /** 原生解析器版本（如 3.0.4） */
  parserVersion: string | null;
  /** OCR 能力（预留未开放，未接入时为 null） */
  ocr: CapabilityRef | null;
  /** 版面分析能力（预留未开放） */
  layout: CapabilityRef | null;
  /** 表格识别能力（预留未开放） */
  table: CapabilityRef | null;
}

/** 能力引用（模型名 + 版本） */
export interface CapabilityRef {
  model: string | null;
  version: string | null;
}

/**
 * 解析环节运行统计（后端 LineageParseStatsVO）：由解析产物本体汇总。
 *
 * <p>每项都可能为 null（产物里取不到），取值一律走 {@link statNumber} 之类的兜底，
 * 不直接参与运算。
 */
export interface LineageParseStats {
  /** 页数（解析器回填的判定单元数；取不到时回落文件引用的页数，仍取不到为 null） */
  pageCount: number | null;
  /** 元素总数 */
  elementCount: number | null;
  /** 正文类元素数 */
  bodyCount: number | null;
  /** 表格数 */
  tableCount: number | null;
  /** 图片数 */
  imageCount: number | null;
  /** 页眉页脚数 */
  headerFooterCount: number | null;
  /** 质量告警数（后端按产物 quality.warnings 计数） */
  warningCount: number | null;
  /** 未解析出内容的单元数（无问题时为 null） */
  failedUnitCount: number | null;
  /** 未解析出内容的起始单元号 */
  failedFrom: number | null;
  /** 未解析出内容的结束单元号 */
  failedTo: number | null;
  /** 本次运行耗时（毫秒） */
  durationMs: number | null;
}

/** 切片环节统计（后端 ChunkStatsVO）：片数、分布、片长与策略参数 */
export interface ChunkStats {
  chunkCount?: number | null;
  sectionCount?: number | null;
  paragraphCount?: number | null;
  tableCount?: number | null;
  imageCount?: number | null;
  fallbackCount?: number | null;
  parentCount?: number | null;
  orphanCount?: number | null;
  totalChars?: number | null;
  avgChars?: number | null;
  maxChars?: number | null;
  overTargetMaxCount?: number | null;
  overSoftMaxCount?: number | null;
  minMergeLen?: number | null;
  underMinMergeCount?: number | null;
  routedElementCount?: number | null;
  skippedElementCount?: number | null;
  multiSourceChunkCount?: number | null;
  avgSourceElementCount?: number | null;
  targetMaxLen?: number | null;
  softMaxLen?: number | null;
  fallbackLen?: number | null;
  fallbackOverlap?: number | null;
  durationMs?: number | null;
}

/** 预处理环节统计（后端 PreprocessStatsVO）：处置分布、字段规范化与耗时 */
export interface PreprocessStats {
  matched?: number | null;
  changed?: number | null;
  elementCount?: number | null;
  retainedCount?: number | null;
  retentionPercent?: number | null;
  excludedCount?: number | null;
  repeatedCount?: number | null;
  chunkSkippedCount?: number | null;
  markedCount?: number | null;
  fieldCount?: number | null;
  changedCount?: number | null;
  encodingCount?: number | null;
  tidyCount?: number | null;
  statusCounts?: Record<string, number> | null;
  fieldTypeCounts?: Record<string, number> | null;
  pageFrom?: number | null;
  pageTo?: number | null;
  durationMs?: number | null;
}

/** 组装环节统计（后端 StructureStatsVO）：元素构成、层级、溯源与续表 */
export interface StructureStats {
  elementCount?: number | null;
  titleCount?: number | null;
  chapterCount?: number | null;
  level1Count?: number | null;
  level2Count?: number | null;
  level3PlusCount?: number | null;
  parentChildCount?: number | null;
  continuationCount?: number | null;
  suspectedContinuationCount?: number | null;
  tracedCount?: number | null;
  provenanceCoverage?: number | null;
  withoutBboxCount?: number | null;
  repeatedSegmentCount?: number | null;
  repeatedPageCount?: number | null;
  noisePageCount?: number | null;
  pageCount?: number | null;
  continuationFromPage?: number | null;
  continuationToPage?: number | null;
  schemaVersion?: string | null;
  durationMs?: number | null;
}

/** 向量化环节统计（后端 EmbedStatsVO）：集合表聚合的记录数与缓存命中数 */
export interface EmbedStats {
  recordCount?: number | null;
  cachedCount?: number | null;
}

/** 环节统计：执行树节点与详情按环节取具体子类型 */
export type StageStats = ChunkStats | PreprocessStats | StructureStats | EmbedStats;

/** 是否切片统计（chunkCount 为切片独有键） */
export function isChunkStats(stats: StageStats | null | undefined): stats is ChunkStats {
  return stats !== null && stats !== undefined && 'chunkCount' in stats;
}

/** 是否预处理统计（retainedCount 为预处理独有键） */
export function isPreprocessStats(stats: StageStats | null | undefined): stats is PreprocessStats {
  return stats !== null && stats !== undefined && 'retainedCount' in stats;
}

/** 是否组装统计（chapterCount 为组装独有键） */
export function isStructureStats(stats: StageStats | null | undefined): stats is StructureStats {
  return stats !== null && stats !== undefined && 'chapterCount' in stats;
}

/** 执行链节点（后端 LineageNodeVO）：一个环节的一次任务运行 */
export interface LineageNode {
  taskId: string;
  /** 环节（PipelineStage 枚举名） */
  stage: string;
  status: string;
  errorCode: string | null;
  errorMsg: string | null;
  /** 策略版本（PREPROCESS/CHUNK/EMBED 有；PARSE/STRUCTURE 为空） */
  strategyVersion: string | null;
  /** 能力快照（PARSE/STRUCTURE 有；有策略的环节为空） */
  capability: LineageCapability | null;
  /** 该次运行产出的产物 ID（成功有产物时非空） */
  productId: string | null;
  artifactId: string | null;
  contentHash: string | null;
  /** 环节统计：CHUNK=ChunkStats、EMBED=EmbedStats、PREPROCESS=PreprocessStats、STRUCTURE=StructureStats */
  stats: StageStats | null;
  /** 解析环节运行统计（仅 PARSE 且该次运行有产物时非空） */
  parseStats: LineageParseStats | null;
  /** 解析环节摘要行文案（后端按状态与问题单元生成；解析进行中为空） */
  parseSummary: string | null;
  /** 环节摘要行文案（STRUCTURE 等通用环节；产物不可读时为空，不陈述结论） */
  stageSummary: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

/**
 * 统计键名 → 中文标签。
 *
 * <p>键名由后端各环节自定义（不可控），映射放前端：后端保持"机器可读的键"，
 * 展示文案归前端，这样加环节时前端明确知道自己有没有漏配。
 * 未在表里的键回落显示原键名（不会空白）。
 */
const STAT_LABELS: Record<string, string> = {
  chunkCount: '切片数',
  recordCount: '向量数',
  cachedCount: '命中缓存',
  matched: '匹配',
  changed: '改动',
};

/** 统计项：中文标签 + 值。键名未映射时标签回落原键名 */
export function statEntry(key: string, value: string): { label: string; value: string } {
  return { label: STAT_LABELS[key] ?? key, value };
}

/** 数值兜底：可用的数原样返回，缺失或非有限值返回 null */
export function statNumber(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** 计数展示：千分位；缺失显示 `—`（不回落 0，让"没有"和"是零"分得开） */
export function formatCount(value: number | null | undefined): string {
  const num = statNumber(value);
  return num === null ? '—' : num.toLocaleString('en-US');
}

/** 耗时展示：秒保留一位小数，一分钟以上进位到分；缺失或非正数显示 `—` */
export function formatDuration(value: number | null | undefined): string {
  const ms = statNumber(value);
  if (ms === null || ms <= 0) {
    return '—';
  }
  if (ms < 60_000) {
    return `${(ms / 1000).toFixed(1)}s`;
  }
  return `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s`;
}

/**
 * 能力快照 → 一行可读文案（如 `pdfbox 3.0.4`）。
 *
 * <p>接入 ocr/layout/table 后追加它们的名称，当前三者均为 null，不出现。
 * 全空时返回 null。
 */
export function capabilityText(capability: LineageCapability | null): string | null {
  if (!capability) {
    return null;
  }
  const parts: string[] = [];
  const parser = [capability.parserName, capability.parserVersion].filter(Boolean).join(' ');
  if (parser) {
    parts.push(parser);
  }
  for (const ref of [capability.ocr, capability.layout, capability.table]) {
    if (ref?.model) {
      parts.push([ref.model, ref.version].filter(Boolean).join(' '));
    }
  }
  return parts.length > 0 ? parts.join(' · ') : null;
}

/** 执行链血缘边（后端 LineageEdgeVO） */
export interface LineageEdge {
  fromTaskId: string;
  toTaskId: string;
}

/** 执行树聚合（后端 LineageVO） */
export interface Lineage {
  fileResultId: string;
  nodes: LineageNode[];
  edges: LineageEdge[];
}

/** 环节子步骤（后端 StepLogVO）：详情抽屉里用于定位"卡在哪一步" */
export interface StageStep {
  stepName: string;
  /** SUCCESS / FAILED */
  status: string | null;
  capabilityVersion: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  /** 耗时（毫秒） */
  duration: number | null;
  warningCount: number | null;
  error: string | null;
}

/**
 * 环节详情公共部分（后端 StageDetailVO）：解析详情与组装详情都继承它。
 *
 * <p>两个环节的详情接口各自的专属字段不同（解析给 `parseStats`，组装给 `summary`/`outline` 等），
 * 公共部分在这里只声明一次。
 */
export interface StageDetailCommon<TStats extends StageStats = StageStats> {
  fileResultId: string;
  taskId: string | null;
  stage: string;
  status: string | null;
  errorCode: string | null;
  errorMsg: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  artifactId: string | null;
  contentHash: string | null;
  /** 能力快照（JSON 文本；后端未解析，页面不消费） */
  capabilitySnapshot: string | null;
  /** 子步骤列表 */
  steps: StageStep[] | null;
  /**
   * 环节统计（读产物现算）：与执行树节点的 `stats` 同一份口径。
   *
   * <p>具体类型由各环节详情接口绑定；计数走 {@link statNumber} 兜底。产物不可读时为 null。
   */
  stageStats: TStats | null;
  /** 环节摘要行文案（产物不可读导致统计缺失时为空，不陈述结论） */
  stageSummary: string | null;
}

/** 解析详情（后端 ParseDetailVO）：抽屉头部、结论、告警与子步骤的数据源 */
export interface ParseDetail extends StageDetailCommon {
  /** 质量告警文案（无产物时为空列表） */
  warnings: string[] | null;
  /** 解析统计（产物不可读或尚无产物时为 null） */
  parseStats: LineageParseStats | null;
  /** 解析结论文案（统计缺失时为 null） */
  parseSummary: string | null;
}

/** 组装统计（后端 StructureSummaryVO）：从统一文档产物推导 */
export interface StructureSummary {
  elementCount: number | null;
  titleCount: number | null;
  paragraphCount: number | null;
  tableCount: number | null;
  imageCount: number | null;
  relationCount: number | null;
  conflictCount: number | null;
  warningCount: number | null;
}

/** 组装冲突记录（后端 StructureConflictVO）：PRIMARY 为主路、BACKUP 为被裁决方 */
export interface StructureConflict {
  primaryElementId: string | null;
  backupElementId: string | null;
  message: string | null;
}

/**
 * 组装大纲元素（后端 StructureOutlineVO）：按阅读顺序排列的统一文档元素。
 *
 * <p>左栏按元素类型把大纲排成一份文档；TABLE 的行列明细走
 * {@link StructureOutlineItem.cells}（产物里有明细才有，没有则按行列数占位）。
 */
export interface StructureOutlineItem {
  elementId: string | null;
  /** 元素类型（UnifiedElementType 枚举名） */
  type: string | null;
  text: string | null;
  /** 标题层级（TITLE 专属，1 起） */
  level: number | null;
  page: number | null;
  pageRange: number[] | null;
  rows: number | null;
  cols: number | null;
  conflictStatus: string | null;
  caption: string | null;
  /** 表格单元格（TABLE 专属；产物无明细时为空） */
  cells: StructureCellItem[] | null;
}

/** 表格单元格（后端 StructureCellVO）：行列从 0 起，与产物里的单元格同口径 */
export interface StructureCellItem {
  row: number | null;
  col: number | null;
  text: string | null;
  isHeader: boolean | null;
}

/** 组装详情（后端 StructureDetailVO） */
export interface StructureDetail extends StageDetailCommon<StructureStats> {
  /** 组装统计（产物不可读时为 null） */
  summary: StructureSummary | null;
  warnings: string[] | null;
  conflicts: StructureConflict[] | null;
  /** 文档内容大纲（按阅读顺序全量，含标题/段落/表格等） */
  outline: StructureOutlineItem[] | null;
}

/** 预处理统计（后端 PreprocessSummaryVO）：从派生视图推导的汇总指标 */
export interface PreprocessSummary {
  /** 视图元素总数 */
  elementCount: number | null;
  /** 检索文本与展示文本不同的元素数 */
  changedCount: number | null;
  /** 剔除元素数（不含重复份，不进内容流） */
  excludedCount: number | null;
  /** 重复元素数 */
  repeatedCount: number | null;
  /** 仅标记元素数（标注后仍进检索内容流） */
  markedCount: number | null;
  /** 不进切片的元素数（剔除态 + 重复份） */
  chunkSkippedCount: number | null;
  /** 标准化字段总数 */
  fieldCount: number | null;
  /** 按状态分布（状态名 → 元素数） */
  statusCounts: Record<string, number> | null;
}

/**
 * 预处理视图元素（后端 PreprocessElementVO）：一个统一文档元素的派生副本。
 *
 * <p>三层文本：`rawText` 永为原文、`displayText` 供展示、`normalizedText` 供检索
 * （被剔除的元素该字段为空，表示不进内容流）。
 */
export interface PreprocessElement {
  /** 源元素 ID（与产物元素 id 同口径） */
  elementId: string | null;
  type: string | null;
  /** 处置状态（ViewElementStatus 枚举名） */
  status: string | null;
  page: number | null;
  rawText: string | null;
  displayText: string | null;
  normalizedText: string | null;
  /** 处理轨迹（只含非 KEEP 条目：规则 / 动作 / 前后摘要 / 证据） */
  trace: PreprocessTrace[] | null;
  /** 标准化字段（金额 / 日期 / 面积 / 证书号） */
  fields: PreprocessField[] | null;
  /** 表格单元格（TABLE 元素专用） */
  cells: PreprocessCell[] | null;
}

/** 预处理处理轨迹（后端 PreprocessTraceVO）：单次规则命中的摘要 */
export interface PreprocessTrace {
  /** 命中规则名（如 encoding-clean-v1） */
  rule: string | null;
  /** 字段类型（PreprocessFieldType 枚举名；可空） */
  field: string | null;
  /** 动作（MARK / REPLACE / EXCLUDE / KEEP / EXTRACT / MANUAL_REVIEW） */
  action: string | null;
  before: string | null;
  after: string | null;
  evidence: string | null;
}

/** 预处理标准化字段（后端 PreprocessFieldVO） */
export interface PreprocessField {
  field: string | null;
  value: string | null;
  unit: string | null;
  rule: string | null;
}

/** 预处理表格单元格（后端 PreprocessCellVO） */
export interface PreprocessCell {
  cellId: string | null;
  text: string | null;
  row: number | null;
  col: number | null;
  isHeader: boolean | null;
  normalizedText: string | null;
}

/** 预处理详情（后端 PreprocessDetailVO） */
export interface PreprocessDetail extends StageDetailCommon<PreprocessStats> {
  /** 预处理统计（产物不可读时为 null） */
  summary: PreprocessSummary | null;
  /** 视图元素（按阅读顺序全量） */
  elements: PreprocessElement[] | null;
}

/** 切片统计（后端 ChunkSummaryVO）：最新切片集合的汇总指标 */
export interface ChunkSummary {
  /** 切片总数（含父片） */
  chunkCount: number | null;
  /** 父片数（contentType=SECTION） */
  parentChunkCount: number | null;
  /** 子片数（parentChunkId 非空） */
  childChunkCount: number | null;
  totalChars: number | null;
  avgChars: number | null;
  /** 内容类型分布（ChunkContentType 枚举名 → 片数） */
  typeCounts: Record<string, number> | null;
}

/**
 * 切片项（后端 ChunkItemVO）：wb_knowledge_chunk 行的展示副本。
 *
 * <p>`sourceElementCount` 只给个数不展开列表（父片的溯源是子片并集，条数无界）；
 * `fallbackReason` 只在兜底片上有值。
 */
export interface ChunkItem {
  /** 集合内切片 ID（chunk-0001 起） */
  chunkId: string | null;
  /** 父片 ID（父片本身为空） */
  parentChunkId: string | null;
  /** 切片内容（normalizedText 口径） */
  content: string | null;
  /** 内容类型（ChunkContentType 枚举名） */
  contentType: string | null;
  titlePath: string | null;
  /** 页码范围（如 1-3） */
  pageRange: string | null;
  /** 表格引用（组装环节表元素 ID） */
  tableRef: string | null;
  /** 集合内顺序（1 起；父片全在前，不是阅读序） */
  orderNo: number | null;
  charCount: number | null;
  tokenCount: number | null;
  /** 来源元素个数 */
  sourceElementCount: number | null;
  /** 兜底原因（非兜底片为空） */
  fallbackReason: string | null;
}

/** 切片详情（后端 ChunkDetailVO） */
export interface ChunkDetail extends StageDetailCommon<ChunkStats> {
  /** 切片统计（无集合时为 null） */
  summary: ChunkSummary | null;
  /** 切片列表（集合内顺序；无集合时为空列表） */
  chunks: ChunkItem[] | null;
}

/**
 * 元素边界框（后端 extra.bbox）：单位点（pt）、左上角原点。
 *
 * <p>原文预览按它在页面上画高亮框；Office 元素可空。
 */
export interface ElementBBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 产物内容项的环节专属附加信息（后端 StageContentExtraVO）；未使用的字段不出现 */
export interface StageContentExtra {
  /** 解析：元素来源（native / ocr 等） */
  source?: string | null;
  /** 解析：解析器标识 */
  provider?: string | null;
  /** 文档页码（解析 / 组装 / 预处理） */
  page?: number | null;
  /** 表格区域行数（解析 / 组装） */
  rows?: number | null;
  /** 表格区域列数（解析 / 组装） */
  cols?: number | null;
  /** 元素边界框（原文预览高亮用） */
  bbox?: ElementBBox | null;
  /** 解析：Word 段落样式名 */
  style?: string | null;
  /** 解析：Excel 工作表名 */
  sheetName?: string | null;
  /** 解析：Excel 单元格行下标（0 基） */
  row?: number | null;
  /** 解析：Excel 单元格列下标（0 基） */
  col?: number | null;
  /** 解析：合并单元格行跨度 */
  rowSpan?: number | null;
  /** 解析：合并单元格列跨度 */
  colSpan?: number | null;
  /** 解析：是否表头单元格 */
  isHeader?: boolean | null;
  /** 解析 / 预处理：表格单元格明细（二维数组的 JSON 文本） */
  cells?: string | null;
  /** 组装：标题层级 */
  level?: number | null;
  /** 预处理：元素原文 */
  rawText?: string | null;
  /** 预处理：结构标记（页眉页脚 / 目录 / 重复段等） */
  marks?: string[] | null;
  /** 预处理：抽取出的规范化字段（JSON 文本） */
  normalizedFields?: string | null;
  /** 预处理：非 KEEP 的处置轨迹（JSON 文本） */
  trace?: string | null;
  /** 切片 / 向量化：切片 ID */
  chunkId?: string | null;
  /** 切片：内容类型 */
  contentType?: string | null;
  /** 切片：标题路径 */
  titlePath?: string | null;
  /** 切片 / 向量化：字符数 */
  charCount?: number | null;
  /** 切片 / 向量化：估算 token 数 */
  tokenCount?: number | null;
  /** 切片：集合内顺序号 */
  orderNo?: number | null;
  /** 切片：父片 ID */
  parentChunkId?: string | null;
  /** 切片：覆盖的页码区间 */
  pageRange?: string | null;
  /** 切片：表格来源引用 */
  tableRef?: string | null;
  /** 向量化：是否命中缓存 */
  cacheHit?: boolean | null;
  /** 向量化：模型调用请求 ID */
  requestId?: string | null;
}

/** 产物内容项（后端 StageContentItemVO） */
export interface StageContentItem {
  /** 对齐键（解析 / 组装 / 预处理 = 产物元素 ID，切片 = 片 ID） */
  alignKey: string | null;
  /** 集合内顺序（渲染行序；切片是集合内顺序号 orderNo） */
  seq: number | null;
  type: string | null;
  status: string | null;
  /** 展示文本（可能大段，页面折叠） */
  display: string | null;
  normalized: string | null;
  /** 环节专属字段：解析=source/page/rows/cols/bbox、切片=chunkId/titlePath/orderNo/pageRange 等 */
  extra: StageContentExtra | null;
}

/** 从 extra 里取边界框：字段缺失或数值非法时返回 null（不画高亮） */
export function bboxOf(extra: StageContentExtra | null): ElementBBox | null {
  const raw = extra?.bbox;
  if (raw === null || raw === undefined) {
    return null;
  }
  const x = statNumber(raw.x);
  const y = statNumber(raw.y);
  const width = statNumber(raw.width);
  const height = statNumber(raw.height);
  if (x === null || y === null || width === null || height === null || width <= 0 || height <= 0) {
    return null;
  }
  return { x, y, width, height };
}

/** 从 extra 里取文档页码：缺失或非正数返回 null */
export function pageOf(extra: StageContentExtra | null): number | null {
  const page = statNumber(extra?.page ?? null);
  return page === null || page < 1 ? null : Math.trunc(page);
}

/** 产物内容分页响应（后端 StageContentVO） */
export interface StageContentPage {
  fileResultId: string;
  stage: string;
  taskId: string | null;
  /** 该次运行产物是否可用（false = 无任务/无产物，items 为空） */
  latest: boolean | null;
  /** 本次生效的文档页过滤（未过滤时为空） */
  docPage: number | null;
  /** 当前页内容项 */
  items: StageContentItem[] | null;
  /** 该次运行产物内容总条数（不受分页影响；按 docPage 过滤后为该页条数） */
  total: number | null;
  current: number | null;
  size: number | null;
  /** 是否还有内容未返回 */
  truncated: boolean | null;
}

/**
 * 触发环节执行的返回（后端 StageTriggerVO / PreprocessTriggerVO 等）。
 *
 * <p>接口是**异步**的：只返回新任务的 ID，执行结果要另行轮询 lineage 获取。
 */
export interface StageTriggerResult {
  pipelineTaskId: string | null;
  fileResultId?: string;
  /** 实际使用的策略版本（仅预处理/切片/向量化返回），可核对是否与预期一致 */
  strategyVersion?: string | null;
}

/**
 * 执行树节点的渲染数据（Vue Flow 自定义节点的 data 载荷）。
 *
 * <p>图组件与节点组件共用同一份定义。
 */
export interface ChainNodeData {
  /** 该节点对应的运行 */
  node: LineageNode;
  /** 是否在用户选中的路径上 */
  onPath: boolean;
  /** 是否是选中路径的末端（从这里触发下游） */
  pathEnd: boolean;
  /** 是否命中知识库当前绑定的策略 */
  hit: boolean;
  /** 出度（下游运行数）：决定节点右侧铺几个源连接桩，避免多次触发的边起点重叠 */
  outCount: number;
  /**
   * 该文件不能继续执行的原因文案；可以继续时为空串。
   *
   * <p>由 `ChainGraph` 在组装 `data` 时按下发值填入，节点据此禁用「触发下一环节」
   * 并把这个原因显示在按钮提示上。
   */
  storageBlockReason: string;
  /**
   * 点击卡片上「触发下一环节」时的回调。
   *
   * <p>**用回调而不是 emits**：Vue Flow 的自定义节点是它内部渲染的，
   * `emit` 到不了页面组件，得先经 `ChainGraph` 再转发一层；而回调由 `ChainGraph`
   * 在组装 `data` 时注入，是它自己的方法（不需要 `getCurrentInstance`）。
   */
  onTrigger?: () => void;
  /** 点击卡片上「详情」时的回调（同上，由 `ChainGraph` 注入） */
  onDetail?: () => void;
}

/** 节点在图上的坐标 */
export interface NodePosition {
  x: number;
  y: number;
}

/**
 * 各文件的节点坐标记忆。
 *
 * <p>由**页面持有**（而不是图组件内部）：切文件时图组件可能被重建，
 * 而且 `selectedFileId` 立即变、`lineage` 要等接口返回，两者之间存在竞态窗口，
 * 组件内无法可靠判断"这份坐标属于哪个文件"。挂在 key 上就没有归属歧义。
 */
export type NodePositionStore = Map<string, Map<string, NodePosition>>;

/** 知识库-策略绑定（后端 StrategyBindingVO；未绑定时只有 strategyType） */
export interface StrategyBinding {
  /**
   * 知识库 ID。
   *
   * <p>**单体查询**（`GET /knowledge-base/{id}/strategy-binding`）时为 null ——
   * 库 ID 由请求路径给出，返回里不重复；**批量查询**
   * （`GET /knowledge-base/strategy-bindings`）时必有值，是区分各行的标识。
   */
  knowledgeBaseId: string | null;
  strategyType: string;
  strategyVersionId: string | null;
  strategyName: string | null;
  /** 仅版本号（如 v1） */
  strategyVersion: string | null;
}

/**
 * 批量设置绑定的一项（后端 `StrategyBindingsUpdateRequest.StrategyBindItem`）。
 *
 * <p>批量接口要求给全 {@link STRATEGY_BINDING_TYPES} 且每项 `strategyVersionId` 都非空。
 */
export interface StrategyBindItem {
  strategyType: string;
  strategyVersionId: string;
}

/** 有策略的环节及其绑定类型（解析/组装无策略，不参与绑定比对） */
export const STRATEGY_BINDING_TYPES = ['PREPROCESS', 'CHUNK', 'EMBED'] as const;
