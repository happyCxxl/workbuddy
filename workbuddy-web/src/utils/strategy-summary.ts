import {
  CHUNK_PIPELINE_TOGGLES,
  CHUNK_ROUTE_LABELS,
  EMBED_MODELS,
  PREPROCESS_ACTION_LABELS,
  PREPROCESS_ACTION_RULES,
  PREPROCESS_FIELD_KEYS,
  PREPROCESS_FIELD_LABELS,
  PREPROCESS_RULE_LABELS,
  PREPROCESS_TIDY_KEYS,
  PREPROCESS_TIDY_LABELS,
  PREPROCESS_TOGGLE_RULES,
  RETRIEVAL_CHANNELS,
  RETRIEVAL_FUSION_MODES,
  RETRIEVAL_POSTPROCESS_MODES,
  RETRIEVAL_RERANK_MODES,
  algorithmLabel,
  parseChunkConfig,
  parseEmbedConfig,
  parsePreprocessConfig,
  parseRetrievalConfig,
  preprocessAction,
  preprocessToggle,
} from '@/types/strategy-config';
import type { ChunkRouteKey, PreprocessRuleRaw } from '@/types/strategy-config';

/** 摘要条目：`k` 为配置项名，`v` 为中文可读值 */
export interface SummaryItem {
  k: string;
  v: string;
}

/** 摘要结果：items 用于键值列表，chips 用于列表卡片上的紧凑标签 */
export interface ConfigSummary {
  items: SummaryItem[];
  /** 列表卡片只展示前几条，避免卡片被撑爆 */
  chips: SummaryItem[];
  /** 配置解析失败（脏数据）时为 true，列表据此给出提示而不是显示空配置 */
  broken: boolean;
}

/** 列表卡片上最多展示几条 */
const CHIP_LIMIT = 3;

function toSummary(items: SummaryItem[], broken = false): ConfigSummary {
  return { items, chips: items.slice(0, CHIP_LIMIT), broken };
}

/** 值缺失时的占位（与「关」「未配置」区分开：这里是配置里根本没有这一项） */
const ABSENT = '—';

function onOff(value: string | undefined): string {
  return value === 'ON' ? '开' : '关';
}

/**
 * 取某条规则的 params。
 *
 * <p>用「键是否存在」而不是可选链判断：TS 把索引访问视为非可选（`noUncheckedIndexedAccess` 未开），
 * 写 `?.` 会被 lint 判为多余；而运行时配置里确实可能没有这条规则。
 */
function ruleParamsOf(
  rules: Record<string, PreprocessRuleRaw>,
  key: string,
): Record<string, string> | null {
  if (!Object.hasOwn(rules, key)) {
    return null;
  }
  const rule: PreprocessRuleRaw = rules[key];
  return rule.params ?? null;
}

/**
 * 预处理摘要：动作类规则说人话，开关类规则只说开着的。
 *
 * <p>开关类规则只报「开了哪些」（全列会很长，且大部分是默认开的）。
 */
function summarizePreprocess(snapshot: string | null): ConfigSummary {
  const cfg = parsePreprocessConfig(snapshot);
  const items: SummaryItem[] = [];

  for (const key of PREPROCESS_ACTION_RULES) {
    const rule: PreprocessRuleRaw | undefined = cfg.rules[key];
    items.push({
      k: PREPROCESS_RULE_LABELS[key] ?? key,
      v: PREPROCESS_ACTION_LABELS[preprocessAction(key, rule)] ?? ABSENT,
    });
  }

  const enabledToggles = PREPROCESS_TOGGLE_RULES.filter((key) =>
    preprocessToggle(key, cfg.rules[key]),
  );
  items.push({
    k: '内容整理',
    v:
      enabledToggles.length === 0
        ? '全关'
        : enabledToggles.map((key) => PREPROCESS_RULE_LABELS[key] ?? key).join(' / '),
  });

  // 子开关只在规则开启时才有意义，这里额外补一条明细
  const fieldParams = ruleParamsOf(cfg.rules, 'field');
  if (preprocessToggle('field', cfg.rules.field) && fieldParams) {
    const on = PREPROCESS_FIELD_KEYS.filter((key) => fieldParams[key] === 'ON');
    items.push({
      k: '字段标准化范围',
      v:
        on.length === 0 ? '未选' : on.map((key) => PREPROCESS_FIELD_LABELS[key] ?? key).join(' / '),
    });
  }
  const tidyParams = ruleParamsOf(cfg.rules, 'tidy');
  if (preprocessToggle('tidy', cfg.rules.tidy) && tidyParams) {
    const on = PREPROCESS_TIDY_KEYS.filter((key) => tidyParams[key] === 'ON');
    items.push({
      k: '文本整理范围',
      v: on.length === 0 ? '未选' : on.map((key) => PREPROCESS_TIDY_LABELS[key] ?? key).join(' / '),
    });
  }

  const tocParams = ruleParamsOf(cfg.rules, 'toc');
  if (tocParams) {
    const parts = Object.entries(tocParams)
      .filter(([, value]) => value !== '')
      .map(([key, value]) => `${key === 'minLinesPerPage' ? '每页≥' : 'run≥'}${value}`);
    if (parts.length > 0) {
      items.push({ k: '目录识别阈值', v: parts.join(' / ') });
    }
  }

  return toSummary(items);
}

/** 切片摘要：四路由各报算法，参数跟在后面；流程开关只报开着的 */
function summarizeChunk(snapshot: string | null): ConfigSummary {
  const cfg = parseChunkConfig(snapshot);
  const items: SummaryItem[] = [];

  for (const route of ['body', 'table', 'image', 'fallback'] as ChunkRouteKey[]) {
    const raw = cfg[route];
    const label = algorithmLabel(route, raw?.algorithm);
    const params = raw?.params ?? {};
    const paramText = Object.entries(params)
      .filter(([, value]) => value !== '')
      .map(([, value]) => value)
      .join('/');
    items.push({
      k: CHUNK_ROUTE_LABELS[route],
      v: paramText ? `${label} ${paramText}` : label,
    });
  }

  const pipeline = cfg.pipeline ?? {};
  const on = CHUNK_PIPELINE_TOGGLES.filter((item) => pipeline[item.key] === 'ON').map(
    (item) => item.label,
  );
  items.push({ k: '流程开关（开）', v: on.length === 0 ? '全关' : on.join(' / ') });

  return toSummary(items);
}

/** 向量化摘要：模型 + 维度/度量 + 批处理 */
function summarizeEmbed(snapshot: string | null): ConfigSummary {
  const cfg = parseEmbedConfig(snapshot);
  const modelKey = cfg.model ?? '';
  const catalog = EMBED_MODELS.find((item) => item.key === modelKey);
  const items: SummaryItem[] = [
    { k: '模型', v: modelKey || ABSENT },
    {
      k: '维度 / 度量',
      v: `${cfg.dimension ?? catalog?.dimension ?? ABSENT} / ${cfg.metric ?? catalog?.metric ?? ABSENT}`,
    },
    {
      k: '批大小 / 上限',
      v: `${cfg.batchSize ?? ABSENT} / ${cfg.batchLimit ?? catalog?.batchLimit ?? ABSENT}`,
    },
    { k: '复用向量账本', v: onOff(cfg.cacheEnabled) },
    { k: '父片向量化', v: onOff(cfg.includeParent) },
    { k: '跳过空文本', v: onOff(cfg.skipEmpty) },
  ];
  return toSummary(items);
}

/** 检索摘要：通道 / 融合 / 前后处理 / TopK */
function summarizeRetrieval(snapshot: string | null): ConfigSummary {
  const cfg = parseRetrievalConfig(snapshot);
  const channel =
    RETRIEVAL_CHANNELS.find((item) => item.key === cfg.channel)?.label ?? cfg.channel ?? ABSENT;
  const fusionMode = cfg.fusion?.mode;
  const fusionLabel =
    RETRIEVAL_FUSION_MODES.find((item) => item.key === fusionMode)?.label ?? fusionMode ?? ABSENT;
  const rerank = cfg.rerank?.mode ?? 'NONE';
  const rerankLabel = RETRIEVAL_RERANK_MODES.find((item) => item.key === rerank)?.label ?? rerank;
  const post = cfg.postprocess?.mode ?? 'NONE';
  const postLabel = RETRIEVAL_POSTPROCESS_MODES.find((item) => item.key === post)?.label ?? post;

  const items: SummaryItem[] = [
    { k: '召回通道', v: channel },
    {
      k: '融合',
      v: fusionMode === 'RRF' ? `${fusionLabel} k=${cfg.fusion?.rrfK ?? ABSENT}` : fusionLabel,
    },
    { k: '重排', v: rerankLabel },
    { k: '后处理', v: postLabel },
    { k: 'TopK', v: String(cfg.topK ?? ABSENT) },
  ];
  if (cfg.scoreThreshold !== undefined && cfg.scoreThreshold !== 0) {
    items.push({ k: '分数阈值', v: String(cfg.scoreThreshold) });
  }
  return toSummary(items);
}

/**
 * 按类型生成配置摘要。
 *
 * <p>空配置返回 `broken=false` 但条目为空的摘要——它只是「没配置」，不是坏数据；
 * JSON 解析失败才标记 `broken`，列表据此给不同提示。
 */
export function summarizeConfig(type: string, snapshot: string | null): ConfigSummary {
  if (!snapshot || snapshot.trim() === '') {
    return { items: [], chips: [], broken: false };
  }
  try {
    JSON.parse(snapshot);
  } catch {
    return { items: [], chips: [], broken: true };
  }
  switch (type) {
    case 'PREPROCESS':
      return summarizePreprocess(snapshot);
    case 'CHUNK':
      return summarizeChunk(snapshot);
    case 'EMBED':
      return summarizeEmbed(snapshot);
    case 'RETRIEVAL':
      return summarizeRetrieval(snapshot);
    default:
      return toSummary([]);
  }
}
