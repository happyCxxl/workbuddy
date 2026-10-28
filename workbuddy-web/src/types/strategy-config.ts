import type { StrategyVersion } from '@/types/strategy';

/**
 * 策略管理页的类型与配置口径。
 *
 * <p>配置快照是 `configSnapshot` JSON 文本，四种类型的结构完全不同：
 *
 * <ul>
 *   <li>{@link PreprocessConfig}：规则 → 动作（KEEP/MARK/EXCLUDE）或开关</li>
 *   <li>{@link ChunkConfig}：四路由算法 + 参数 + 流程开关</li>
 *   <li>{@link EmbedConfig}：模型 + 批处理 + 向量维度/度量</li>
 *   <li>{@link RetrievalConfig}：通道 + 融合 + 前后处理 + TopK</li>
 * </ul>
 *
 * <p>**解析一律做成宽松的**：字段缺失或类型不对时回落默认值而不是抛错。
 * 后端对配置有归一化与校验（`ChunkAlgorithmSpec.validate` 等），前端只负责呈现与编辑，
 * 遇到不认识的键要**原样保留**、不要丢掉，避免保存时抹掉后端写入的字段。
 */

// ============================================================
// 类型与状态
// ============================================================

/** 策略类型：与后端 SUPPORTED_TYPES 对齐 */
export const STRATEGY_TYPES = ['PREPROCESS', 'CHUNK', 'EMBED', 'RETRIEVAL'] as const;

export type StrategyType = (typeof STRATEGY_TYPES)[number];

export const STRATEGY_TYPE_LABELS: Record<string, string> = {
  PREPROCESS: '预处理',
  CHUNK: '切片',
  EMBED: '向量化',
  RETRIEVAL: '检索',
};

/** 侧栏类型切换用的短名（窄栏放不下全称时用） */
export const STRATEGY_TYPE_SHORT_LABELS: Record<string, string> = {
  PREPROCESS: '预处理',
  CHUNK: '切片',
  EMBED: '向量',
  RETRIEVAL: '检索',
};

/** 类型说明：详情页里的一句用途描述 */
export const STRATEGY_TYPE_DESCRIPTIONS: Record<string, string> = {
  PREPROCESS: '决定哪些元素进检索内容流、以及文本怎么整理',
  CHUNK: '决定文档怎么切成检索的最小单元',
  EMBED: '决定切片怎么向量化、用什么模型',
  RETRIEVAL: '决定怎么召回、怎么融合、返回多少条',
};

export function strategyTypeLabel(type: string): string {
  return STRATEGY_TYPE_LABELS[type] ?? type;
}

/** 策略版本状态 */
export const STRATEGY_STATUS_ACTIVE = 'ACTIVE';
export const STRATEGY_STATUS_INACTIVE = 'INACTIVE';

export function isStrategyActive(item: StrategyVersion): boolean {
  return item.status === STRATEGY_STATUS_ACTIVE;
}

// ============================================================
// 预处理配置
// ============================================================

/** 元素处置动作（后端 PreprocessAction） */
export type PreprocessAction = 'KEEP' | 'MARK' | 'EXCLUDE';

export const PREPROCESS_ACTION_LABELS: Record<string, string> = {
  KEEP: '保留',
  MARK: '标记',
  EXCLUDE: '剔除',
};

/** 用「动作」语义的规则：三选一（重复与三条处置规则同形） */
export const PREPROCESS_ACTION_RULES = ['headerFooter', 'toc', 'noise', 'repeat'] as const;

/** 用「开关」语义的规则：开 / 关 */
export const PREPROCESS_TOGGLE_RULES = ['field', 'tidy', 'encoding'] as const;

/** 配置区展示顺序（含自定义规则组，它是链尾第 8 个节点） */
export const PREPROCESS_RULE_ORDER = [
  'headerFooter',
  'toc',
  'noise',
  'repeat',
  'encoding',
  'tidy',
  'field',
  'custom',
] as const;

/** 处置类规则分组（判定在上游环节，本页只决定处置方式） */
export const PREPROCESS_DISPOSE_RULES = ['headerFooter', 'toc', 'noise', 'repeat'] as const;

/** 内容整理类规则分组 */
export const PREPROCESS_ORGANIZE_RULES = ['encoding', 'tidy', 'field'] as const;

export const PREPROCESS_RULE_LABELS: Record<string, string> = {
  headerFooter: '页眉页脚',
  toc: '目录',
  noise: '噪声元素',
  repeat: '重复段落 / 页去重',
  field: '字段标准化',
  tidy: '文本级整理',
  encoding: '乱码与编码清理',
  custom: '自定义规则',
};

export const PREPROCESS_RULE_HINTS: Record<string, string> = {
  headerFooter: '页眉页脚元素（含页码）怎么处置',
  toc: '先按阈值判是不是目录，再决定处置；阈值对三种处置都生效',
  noise: '组装判出的噪声页上的元素；「保留」只统计不处置',
  repeat: '重复份：只留首份（默认）/ 标注保留 / 原样保留',
  field: '金额 / 日期 / 面积 / 证号 四类字段分别开关',
  tidy: '空白 / 折行合并 / 标点 / 软连字符 / 项目符号 / 链接 子规则独立开关',
  encoding: '乱码残留与全角字符清理（NFKC），第 1 步执行',
  custom: '链尾按列表顺序执行的正则；上限 20 条、单条正则 ≤200 字符',
};

/**
 * 未配置时的生效默认值（与后端 `PreprocessAlgorithmSpec` 对齐）：
 * 动作类默认见下，开关类 field / tidy / encoding 默认开。
 */
export const PREPROCESS_DEFAULT_ACTION: Record<string, PreprocessAction | undefined> = {
  headerFooter: 'MARK',
  toc: 'MARK',
  noise: 'MARK',
  repeat: 'EXCLUDE',
};

export const PREPROCESS_DEFAULT_ENABLED: Record<string, boolean> = {
  field: true,
  tidy: true,
  encoding: true,
};

/**
 * 旧开关格式兼容名单：这几条规则历史上只有 enabled 开关，
 * 迁移期按「ON = 剔除 / OFF = 保留」换算（与后端 `PreprocessRule.legacyToggle()` 同一口径）。
 */
export const PREPROCESS_LEGACY_TOGGLE_RULES = ['repeat'] as const;

/** 自定义规则动作（后端 CustomRuleAction） */
export const PREPROCESS_CUSTOM_ACTIONS = [
  { key: 'REMOVE', label: '移除', hint: '命中内容整体移除' },
  { key: 'REPLACE', label: '替换', hint: '按替换文本改写，支持 $1 组引用' },
  { key: 'EXTRACT', label: '提取', hint: '只保留命中，多个命中以空格连接' },
] as const;

/** 自定义规则条数上限（与后端 PreprocessAlgorithmSpec.CUSTOM_RULE_MAX 一致） */
export const PREPROCESS_CUSTOM_RULE_MAX = 20;

/** 字段标准化的四类子开关 */
export const PREPROCESS_FIELD_KEYS = ['amount', 'date', 'area', 'certNo'] as const;

export const PREPROCESS_FIELD_LABELS: Record<string, string> = {
  amount: '金额',
  date: '日期',
  area: '面积',
  certNo: '证号',
};

/** 文本整理的六个子开关 */
export const PREPROCESS_TIDY_KEYS = [
  'whitespace',
  'joinLines',
  'punct',
  'dashes',
  'bullets',
  'urls',
] as const;

export const PREPROCESS_TIDY_LABELS: Record<string, string> = {
  whitespace: '空白与首尾',
  joinLines: '折行段落合并',
  punct: '标点与引号',
  dashes: '软连字符',
  bullets: '项目符号',
  urls: '链接与邮箱',
};

/** 目录识别阈值（仅在 toc 动作为 MARK/EXCLUDE 时有意义） */
export const PREPROCESS_TOC_PARAM_KEYS = ['minLinesPerPage', 'runMinLength'] as const;

export const PREPROCESS_TOC_PARAM_LABELS: Record<string, string> = {
  minLinesPerPage: '每页最少行数',
  runMinLength: '连续 run 最短长度',
};

/** 单条规则的原始结构：动作类用 action，开关类用 enabled，两者都可带 params */
export interface PreprocessRuleRaw {
  action?: string;
  enabled?: string;
  params?: Record<string, string>;
  [key: string]: unknown;
}

export interface PreprocessConfig {
  rules: Record<string, PreprocessRuleRaw>;
  custom?: Record<string, unknown>;
  [key: string]: unknown;
}

// ============================================================
// 切片配置
// ============================================================

/**
 * 切片算法选项。
 *
 * <p>`supported=false` 的是后端预留但未上线的算法（后端枚举里也是这样标的），
 * 下拉里**置灰可见**，并带上未上线原因（`reason`）。
 *
 * <p>参数的**键与范围**与后端 `workbuddy-server/workbuddy-common/.../enums/chunk/ChunkParam` 同源
 * （那是保存校验的唯一事实源）；此处只补中文名与范围提示，改一处要同步另一处。
 */
export interface ChunkParamHint {
  /** 参数中文名 */
  label: string;
  /** 允许的最小值（与后端 ChunkParam 一致） */
  min: number;
  /** 允许的最大值（与后端 ChunkParam 一致） */
  max: number;
}

export interface ChunkAlgorithmOption {
  key: string;
  label: string;
  supported: boolean;
  /** 该算法涉及的参数（键 → 中文名 + 范围） */
  params?: Record<string, ChunkParamHint>;
  /** 未上线原因（`supported=false` 时用于下拉里的说明） */
  reason?: string;
}

export const CHUNK_BODY_ALGORITHMS: ChunkAlgorithmOption[] = [
  {
    key: 'paragraph-aggregate',
    label: '段落聚合',
    supported: true,
    params: {
      targetMaxLen: { label: '目标片长', min: 100, max: 5000 },
      softMaxLen: { label: '软上限', min: 100, max: 8000 },
    },
  },
  {
    key: 'title-boundary',
    label: '标题边界',
    supported: true,
    params: { maxLen: { label: '片长上限', min: 100, max: 20000 } },
  },
  {
    key: 'structure-hybrid',
    label: '结构混合',
    supported: true,
    params: {
      targetMaxLen: { label: '目标片长', min: 100, max: 5000 },
      softMaxLen: { label: '软上限', min: 100, max: 8000 },
    },
  },
  {
    key: 'sentence-aggregate',
    label: '句子聚合',
    supported: true,
    params: {
      targetMaxLen: { label: '目标片长', min: 100, max: 5000 },
      softMaxLen: { label: '软上限', min: 100, max: 8000 },
    },
  },
  {
    key: 'fixed-window',
    label: '固定窗口',
    supported: true,
    params: {
      len: { label: '窗口长度', min: 100, max: 5000 },
      overlap: { label: '重叠长度', min: 0, max: 1000 },
    },
  },
  { key: 'semantic', label: '语义切片', supported: false, reason: '依赖嵌入能力' },
];

export const CHUNK_TABLE_ALGORITHMS: ChunkAlgorithmOption[] = [
  {
    key: 'row-slice',
    label: '行级切片（表头随片）',
    supported: true,
    params: {
      groupThreshold: { label: '短行阈值', min: 5, max: 500 },
      groupSize: { label: '行组大小', min: 1, max: 20 },
    },
  },
  {
    key: 'row-group',
    label: '行组切片',
    supported: true,
    params: {
      groupSize: { label: '行组大小', min: 1, max: 50 },
      maxLen: { label: '组字符上限', min: 100, max: 5000 },
    },
  },
  {
    key: 'whole-table',
    label: '整表一片',
    supported: true,
    params: { maxLen: { label: '片长上限', min: 100, max: 20000 } },
  },
  {
    key: 'context-merged',
    label: '表 + 引导段落',
    supported: true,
    params: {
      leadMaxLen: { label: '引导段截断长度', min: 0, max: 1000 },
      groupThreshold: { label: '短行阈值', min: 5, max: 500 },
      groupSize: { label: '行组大小', min: 1, max: 20 },
    },
  },
];

export const CHUNK_IMAGE_ALGORITHMS: ChunkAlgorithmOption[] = [
  { key: 'caption-placeholder', label: '图注占位', supported: true },
  { key: 'caption-context', label: '图注 + 上下文', supported: false, reason: '依赖邻近正文聚合' },
  { key: 'ocr', label: 'OCR 文字', supported: false, reason: '依赖解析环节的 OCR 能力' },
  { key: 'visual-summary', label: '视觉摘要', supported: false, reason: '依赖多模态模型' },
  { key: 'multimodal', label: '多模态向量', supported: false, reason: '依赖多模态向量模型' },
];

export const CHUNK_FALLBACK_ALGORITHMS: ChunkAlgorithmOption[] = [
  {
    key: 'recursive-length',
    label: '递归降级',
    supported: true,
    params: {
      len: { label: '片长', min: 100, max: 5000 },
      overlap: { label: '重叠', min: 0, max: 1000 },
    },
  },
  {
    key: 'fixed-window',
    label: '固定窗口硬切',
    supported: true,
    params: {
      len: { label: '片长', min: 100, max: 5000 },
      overlap: { label: '重叠', min: 0, max: 1000 },
    },
  },
  { key: 'semantic-boundary', label: '语义断点', supported: false, reason: '依赖嵌入能力' },
  { key: 'none', label: '不兜底', supported: true },
];

/** 切片路由（配置里的四个分区） */
export type ChunkRouteKey = 'body' | 'table' | 'image' | 'fallback';

export const CHUNK_ROUTE_LABELS: Record<ChunkRouteKey, string> = {
  body: '正文',
  table: '表格',
  image: '图片',
  fallback: '超长兜底',
};

export const CHUNK_ROUTE_ALGORITHMS: Record<ChunkRouteKey, ChunkAlgorithmOption[]> = {
  body: CHUNK_BODY_ALGORITHMS,
  table: CHUNK_TABLE_ALGORITHMS,
  image: CHUNK_IMAGE_ALGORITHMS,
  fallback: CHUNK_FALLBACK_ALGORITHMS,
};

/** 流程开关（pipeline 分区） */
export const CHUNK_PIPELINE_TOGGLES = [
  { key: 'parentChild', label: '父子层级（父片 + 子片）', hint: '检索命中子片时展开为父片全文' },
  { key: 'titleInContent', label: '标题写入正文', hint: '切断片时把标题路径拼进片内容' },
  {
    key: 'tableInBodyFlow',
    label: '表格并入正文流',
    hint: '与表格算法「表 + 引导段落」互斥，二者只能开一个',
  },
] as const;

export const CHUNK_PIPELINE_NUMBERS = [
  { key: 'titlePathMaxLevel', label: '标题路径最大层级' },
  { key: 'minMergeLen', label: '碎片合并阈值' },
  { key: 'structureOverlap', label: '结构重叠长度' },
] as const;

export interface ChunkRouteRaw {
  algorithm?: string;
  params?: Record<string, string>;
  [key: string]: unknown;
}

export interface ChunkConfig {
  body?: ChunkRouteRaw;
  table?: ChunkRouteRaw;
  image?: ChunkRouteRaw;
  fallback?: ChunkRouteRaw;
  pipeline?: Record<string, string>;
  [key: string]: unknown;
}

/**
 * 切片配置的互斥关系（后端 `ChunkAlgorithmSpec.validate` 同款，前端联动禁用）。
 *
 * <p>表格并入正文流时，「表 + 引导段落」语义重复，两者不能同时启用。
 */
export function isTableInBodyFlowOn(pipeline: Record<string, string> | undefined): boolean {
  return pipeline?.tableInBodyFlow === 'ON';
}

// ============================================================
// 向量化配置
// ============================================================

/** 向量化模型目录（后端嵌入模型枚举；`enabled=false` 的在下拉里置灰） */
export interface EmbedModelOption {
  key: string;
  label: string;
  dimension: number;
  metric: string;
  contextWindowTokens: number;
  batchLimit: number;
  enabled: boolean;
}

export const EMBED_MODELS: EmbedModelOption[] = [
  {
    key: 'text-embedding-v4',
    label: 'text-embedding-v4（默认）',
    dimension: 1024,
    metric: 'COSINE',
    contextWindowTokens: 8192,
    batchLimit: 64,
    enabled: true,
  },
  {
    key: 'text-embedding-v3',
    label: 'text-embedding-v3（备选）',
    dimension: 1024,
    metric: 'COSINE',
    contextWindowTokens: 8192,
    batchLimit: 64,
    enabled: false,
  },
  {
    key: 'text-embedding-v2',
    label: 'text-embedding-v2（旧版备选）',
    dimension: 1536,
    metric: 'COSINE',
    contextWindowTokens: 2048,
    batchLimit: 64,
    enabled: false,
  },
];

export function embedModelOf(key: string | undefined): EmbedModelOption | undefined {
  return EMBED_MODELS.find((item) => item.key === key);
}

export interface EmbedConfig {
  model?: string;
  docTemplate?: string;
  queryTemplate?: string;
  batchSize?: number;
  timeoutMs?: number;
  maxRetries?: number;
  cacheEnabled?: string;
  includeParent?: string;
  skipEmpty?: string;
  dimension?: number;
  metric?: string;
  normalized?: boolean;
  contextWindowTokens?: number;
  batchLimit?: number;
  [key: string]: unknown;
}

export const EMBED_TOGGLES = [
  { key: 'cacheEnabled', label: '复用向量账本', hint: '内容未变的切片复用上次向量，省调用' },
  { key: 'includeParent', label: '父片也向量化', hint: '默认只向量化子片' },
  { key: 'skipEmpty', label: '跳过空文本', hint: '默认开启' },
] as const;

export const EMBED_NUMBERS = [
  { key: 'batchSize', label: '批大小' },
  { key: 'timeoutMs', label: '单批超时（毫秒）' },
  { key: 'maxRetries', label: '最大重试次数' },
] as const;

export const EMBED_TEMPLATES = [
  { key: 'docTemplate', label: '文档模板', hint: '入库时拼进向量输入，{content} 为切片正文' },
  { key: 'queryTemplate', label: '查询模板', hint: '检索时拼进查询，{query} 为用户问题' },
] as const;

// ============================================================
// 检索配置
// ============================================================

export const RETRIEVAL_CHANNELS = [
  { key: 'VECTOR', label: '仅向量召回' },
  { key: 'FULLTEXT', label: '仅全文召回' },
  { key: 'HYBRID', label: '向量 + 全文混合' },
] as const;

export const RETRIEVAL_FUSION_MODES = [
  { key: 'RRF', label: 'RRF（倒数排名融合）' },
  { key: 'NONE', label: '不融合' },
] as const;

export const RETRIEVAL_PREPROCESS_MODES = [{ key: 'NONE', label: '不做' }] as const;

export const RETRIEVAL_RERANK_MODES = [{ key: 'NONE', label: '不重排' }] as const;

export const RETRIEVAL_POSTPROCESS_MODES = [
  { key: 'NONE', label: '不做' },
  { key: 'PARENT_EXPAND', label: '父片展开（命中子片返回父片全文）' },
] as const;

export interface RetrievalConfig {
  channel?: string;
  fusion?: { mode?: string; rrfK?: number; perChannelLimit?: number; [key: string]: unknown };
  preprocess?: { mode?: string; [key: string]: unknown };
  rerank?: { mode?: string; [key: string]: unknown };
  postprocess?: { mode?: string; [key: string]: unknown };
  topK?: number;
  scoreThreshold?: number;
  [key: string]: unknown;
}

// ============================================================
// 解析与序列化
// ============================================================

/**
 * 宽松解析配置 JSON，返回普通对象。
 *
 * <p>解析失败或不是对象时返回空对象而不是抛错：列表仍要能展示
 * （摘要退化为「配置无法解析」），不能让一条脏数据把整页打挂。
 *
 * <p>**不做泛型**：`{} as T` 对 `T extends object` 而言等于 `{}`（断言没有意义，
 * 会让 lint 的 no-unnecessary-type-assertion 拦下）。类型由各类型的解析函数在边界处收口。
 */
export function parseConfig(snapshot: string | null): Record<string, unknown> {
  if (!snapshot) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(snapshot);
    if (typeof parsed !== 'object' || parsed === null) {
      return {};
    }
    return parsed as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function parsePreprocessConfig(snapshot: string | null): PreprocessConfig {
  const raw = parseConfig(snapshot);
  const rules = raw.rules;
  return {
    ...raw,
    rules: typeof rules === 'object' && rules !== null ? (rules as PreprocessConfig['rules']) : {},
  };
}

export function parseChunkConfig(snapshot: string | null): ChunkConfig {
  return parseConfig(snapshot);
}

export function parseEmbedConfig(snapshot: string | null): EmbedConfig {
  return parseConfig(snapshot);
}

export function parseRetrievalConfig(snapshot: string | null): RetrievalConfig {
  return parseConfig(snapshot);
}

/** 按类型解析：四种配置结构不同，这里只负责分派，返回值按类型收窄 */
export function parseConfigByType(type: string, snapshot: string | null): Record<string, unknown> {
  switch (type) {
    case 'PREPROCESS':
      return parsePreprocessConfig(snapshot);
    case 'CHUNK':
      return parseChunkConfig(snapshot);
    case 'EMBED':
      return parseEmbedConfig(snapshot);
    case 'RETRIEVAL':
      return parseRetrievalConfig(snapshot);
    default:
      return parseConfig(snapshot);
  }
}

/**
 * 取某条预处理规则的动作：优先 `action`；缺 `action` 但带旧开关 `enabled` 时按 ON=EXCLUDE / OFF=KEEP 换算；
 * 两者都没有时按该规则的默认档（重复默认剔除，其余处置类默认标记）。
 */
export function preprocessAction(
  key: string,
  rule: PreprocessRuleRaw | undefined,
): PreprocessAction {
  const value = rule?.action;
  if (value === 'KEEP' || value === 'MARK' || value === 'EXCLUDE') {
    return value;
  }
  return preprocessLegacyAction(key, rule) ?? PREPROCESS_DEFAULT_ACTION[key] ?? 'MARK';
}

/** 旧开关格式换算（只对名单内的规则生效）：ON=EXCLUDE / OFF=KEEP；不适用返回 null */
export function preprocessLegacyAction(
  key: string,
  rule: PreprocessRuleRaw | undefined,
): PreprocessAction | null {
  if (!(PREPROCESS_LEGACY_TOGGLE_RULES as readonly string[]).includes(key)) {
    return null;
  }
  if (rule?.enabled === 'ON') {
    return 'EXCLUDE';
  }
  if (rule?.enabled === 'OFF') {
    return 'KEEP';
  }
  return null;
}

/** 取开关规则状态：没写时按该规则的默认（field / tidy / encoding 默认开） */
export function preprocessToggle(key: string, rule: PreprocessRuleRaw | undefined): boolean {
  if (rule?.enabled === 'ON') {
    return true;
  }
  if (rule?.enabled === 'OFF') {
    return false;
  }
  return PREPROCESS_DEFAULT_ENABLED[key] ?? false;
}

/** 取规则内某子开关状态：没写时按默认开（与后端子参数默认 ON 一致） */
export function preprocessSubToggle(rule: PreprocessRuleRaw | undefined, key: string): boolean {
  const value = rule?.params?.[key];
  if (value === 'ON') {
    return true;
  }
  if (value === 'OFF') {
    return false;
  }
  return true;
}

/** 算法选项查找：找不到返回 undefined（配置里出现了前端不知道的算法） */
export function findAlgorithmOption(
  route: ChunkRouteKey,
  key: string | undefined,
): ChunkAlgorithmOption | undefined {
  if (!key) {
    return undefined;
  }
  return CHUNK_ROUTE_ALGORITHMS[route].find((item) => item.key === key);
}

/** 算法键 → 中文名（未知算法回落键本身） */
export function algorithmLabel(route: ChunkRouteKey, key: string | undefined): string {
  if (!key) {
    return '未配置';
  }
  return findAlgorithmOption(route, key)?.label ?? key;
}
