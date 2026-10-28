/**
 * 预处理配置的示例预览：一段手写节选充当"原文示例"，按当前档位/开关算出"结果示例"。
 *
 * <p>只体现**当前这一条规则**的效果（隔离）：多条规则的效果叠在一起时，差异无法归因到某个配置项；
 * 判定（谁算页眉页脚 / 目录 / 重复 / 噪声）来自上游环节，这里只演示处置与文本整理。
 *
 * <p>示例是静态手写节选，不调接口、不跑后端；变换按后端口径简化实现（NFKC、折行合并、
 * 千分位、日期合法性、面积单位、软连字符、项目符号、链接移除等）。
 */
import {
  PREPROCESS_RULE_LABELS,
  preprocessAction,
  preprocessSubToggle,
  preprocessToggle,
} from '@/types/strategy-config';
import type { PreprocessConfig, PreprocessRuleRaw } from '@/types/strategy-config';

/** 示例行类型（决定它归哪条规则处置） */
export type PreviewKind = 'header' | 'toc' | 'body' | 'dup' | 'noise' | 'footer';

export interface PreviewSampleLine {
  kind: PreviewKind;
  label: string;
  text: string;
}

/** 结果里的文本片段：mark 非空表示这是"原文里的不可见字符"标注 */
export interface PreviewSegment {
  mark?: string;
  text: string;
}

export interface PreviewRow {
  label: string;
  focused: boolean;
  dropped: boolean;
  changed: boolean;
  raw: PreviewSegment[];
  out: PreviewSegment[];
  chips: string[];
}

export interface PreviewImpact {
  excluded: number;
  marked: number;
  changed: number;
}

/** 示例文档节选（与后端无关，纯前端演示用） */
export const PREPROCESS_PREVIEW_SAMPLE: PreviewSampleLine[] = [
  { kind: 'header', label: '页眉', text: 'XX项目招标文件（第 1 页）' },
  { kind: 'toc', label: '目录', text: '第一章 总则 ...... 1' },
  { kind: 'toc', label: '目录', text: '第二章 投标人须知 ...... 3' },
  { kind: 'toc', label: '目录', text: '第三章 评标办法 ...... 7' },
  {
    kind: 'body',
    label: '正文',
    text: '投\u00AD标保证金为人民币叁佰万元整（￥3,000,000.00），于\n2026/8/25 前提交；开标时间 2026/2/30，建筑面积 10㎡。',
  },
  { kind: 'body', label: '正文', text: '证书编号：NO.123456；版权所有© 2026 XX招标代理有限公司' },
  {
    kind: 'body',
    label: '正文',
    text: '电话：13800138000，邮箱 bid@example.com，公告见 https://example.com/bid',
  },
  {
    kind: 'body',
    label: '正文',
    text: '•投标人须具备资质！！（２０２６年版）详见"须知"第 2.1 条\u200B',
  },
  { kind: 'dup', label: '重复段', text: '投标保证金为人民币叁佰万元整（￥3,000,000.00）。' },
  { kind: 'noise', label: '噪声页', text: 'ÿþ¤ 锟斤拷 张\uFFFD\uFFFD 乱码页内容（第 7 页）' },
  { kind: 'footer', label: '页脚', text: '第 1 页 / 共 20 页' },
];

/** 规则 → 它在示例里作用到的行类型 */
const PREVIEW_FOCUS: Record<string, PreviewKind[]> = {
  headerFooter: ['header', 'footer'],
  toc: ['toc'],
  noise: ['noise'],
  repeat: ['dup'],
  encoding: ['body'],
  tidy: ['body'],
  field: ['body'],
  custom: ['body'],
};

const CJK = /[\u3000-\u303F\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]/;

/** 目录阈值：示例一页的目录行数 ≥ minLinesPerPage 才算目录页 */
function tocRecognized(cfg: PreprocessConfig): boolean {
  const min = Number(cfg.rules.toc.params?.minLinesPerPage ?? 3) || 3;
  return PREPROCESS_PREVIEW_SAMPLE.filter((line) => line.kind === 'toc').length >= min;
}

/** 当前档位（只对本规则涉及的行走口径，其余按原文展示） */
function disposition(ruleKey: string, kind: PreviewKind, cfg: PreprocessConfig): string {
  if (!(PREVIEW_FOCUS[ruleKey] ?? []).includes(kind)) {
    return 'NONE';
  }
  if (kind === 'header' || kind === 'footer') {
    return preprocessAction('headerFooter', cfg.rules.headerFooter);
  }
  if (kind === 'toc') {
    return tocRecognized(cfg) ? preprocessAction('toc', cfg.rules.toc) : 'NONE';
  }
  if (kind === 'noise') {
    return preprocessAction('noise', cfg.rules.noise);
  }
  if (kind === 'dup') {
    return preprocessAction('repeat', cfg.rules.repeat);
  }
  return 'NONE';
}

/** 文本整理：按 tidy 子开关逐项改写（只动文本不动结构） */
function applyTidy(rule: PreprocessRuleRaw | undefined, text: string, flags: string[]): string {
  let out = text;
  if (preprocessSubToggle(rule, 'whitespace')) {
    const before = out;
    out = out.replace(/[ \t]{2,}/g, ' ');
    if (out !== before) flags.push('空白');
  }
  if (preprocessSubToggle(rule, 'joinLines')) {
    const before = out;
    out = out.replace(/([^\n])\n\s*([^\n])/g, (_whole, left: string, right: string) =>
      CJK.test(left) || CJK.test(right) ? left + right : `${left} ${right}`,
    );
    if (out !== before) flags.push('折行合并');
  }
  if (preprocessSubToggle(rule, 'punct')) {
    const before = out;
    out = out.replace(/([！？。，、；：])\1+/g, '$1').replace(/"([^"\n]{1,30})"/g, '“$1”');
    if (out !== before) flags.push('标点');
  }
  if (preprocessSubToggle(rule, 'dashes')) {
    const before = out;
    out = out.replace(/\u00AD/g, '');
    if (out !== before) flags.push('软连字符');
  }
  if (preprocessSubToggle(rule, 'bullets')) {
    const before = out;
    out = out.replace(/([•·])(?=[^\s])/g, '$1 ');
    if (out !== before) flags.push('项目符号');
  }
  if (preprocessSubToggle(rule, 'urls')) {
    const before = out;
    out = out.replace(/https?:\/\/\S+/g, '').replace(/[\w.+-]+@[\w-]+\.[\w.]+/g, '');
    if (out !== before) flags.push('链接与邮箱');
  }
  return out;
}

/** 字段标准化：金额 / 日期 / 面积 / 证号；拿不准与日期不存在时保持原文并标出来 */
function applyField(rule: PreprocessRuleRaw | undefined, text: string, flags: string[]): string {
  let out = text;
  if (preprocessSubToggle(rule, 'amount')) {
    const before = out;
    out = out.replace(/\d{1,3}(?:,\d{3})+(?:\.\d+)?/g, (whole) => whole.replace(/,/g, ''));
    if (out !== before) flags.push('金额');
  }
  if (preprocessSubToggle(rule, 'date')) {
    const before = out;
    out = out.replace(
      /(\d{4})[/.](\d{1,2})[/.](\d{1,2})/g,
      (whole, year: string, month: string, day: string) => {
        const y = Number(year);
        const m = Number(month);
        const d = Number(day);
        const date = new Date(Date.UTC(y, m - 1, d));
        const valid =
          date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
        return valid ? `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}` : whole;
      },
    );
    const unresolved = /(\d{4})[/.](\d{1,2})[/.](30|31)/.test(before);
    if (out !== before) flags.push('日期');
    if (unresolved) flags.push('日期·不改写');
  }
  if (preprocessSubToggle(rule, 'area')) {
    const before = out;
    out = out.replace(/(\d+(?:\.\d+)?)\s*(㎡|m2|M2)/g, (_whole, num: string) => `${num}平方米`);
    if (out !== before) flags.push('面积');
  }
  if (preprocessSubToggle(rule, 'certNo') && /(证书编号|证书号|注册号)/.test(out)) {
    flags.push('证号');
  }
  return out;
}

/** 自定义规则：两条示例规则分别是"移除"与"替换" */
function applyCustom(text: string, flags: string[]): string {
  let out = text;
  const removed = out.replace(/版权所有\s*©.*/g, '');
  if (removed !== out) {
    flags.push('自定义·移除');
    out = removed;
  }
  const replaced = out.replace(/(电话[:：])(\d+)/g, '$1***');
  if (replaced !== out) {
    flags.push('自定义·替换');
    out = replaced;
  }
  return out;
}

/** 只算当前这条规则的效果 */
function transform(
  ruleKey: string,
  cfg: PreprocessConfig,
  text: string,
): { text: string; flags: string[] } {
  const flags: string[] = [];
  let out = text;
  if (ruleKey === 'encoding' && preprocessToggle('encoding', cfg.rules.encoding)) {
    const before = out;
    out = out.replace(/[\u200B-\u200D\uFEFF]/g, '').normalize('NFKC');
    if (out !== before) flags.push('编码清理');
  }
  if (ruleKey === 'tidy' && preprocessToggle('tidy', cfg.rules.tidy)) {
    out = applyTidy(cfg.rules.tidy, out, flags);
  }
  if (ruleKey === 'field' && preprocessToggle('field', cfg.rules.field)) {
    out = applyField(cfg.rules.field, out, flags);
  }
  if (ruleKey === 'custom' && cfg.custom?.enabled !== 'OFF') {
    out = applyCustom(out, flags);
  }
  return { text: out, flags: [...new Set(flags)] };
}

/**
 * 文本 → 片段：把不可见字符（零宽 / 软连字符 / 替换字符）拆成标注片段，
 * 不标注时"清理前后"在页面上看不出差别。
 */
export function preprocessSegments(text: string): PreviewSegment[] {
  const segments: PreviewSegment[] = [];
  let buffer = '';
  const push = (mark?: string): void => {
    if (buffer) {
      segments.push(mark ? { mark, text: buffer } : { text: buffer });
      buffer = '';
    }
  };
  for (const char of text) {
    if (char === '\u200B') {
      push();
      segments.push({ mark: '零宽', text: '' });
    } else if (char === '\u00AD') {
      push();
      segments.push({ mark: '软连字符', text: '' });
    } else if (char === '\uFFFD') {
      push();
      segments.push({ mark: '替换字符', text: '' });
    } else {
      buffer += char;
    }
  }
  push();
  return segments;
}

/** 表格展开时的两片段行（只列本规则涉及的行） */
export function preprocessPreviewRows(ruleKey: string, cfg: PreprocessConfig): PreviewRow[] {
  const focus = PREVIEW_FOCUS[ruleKey] ?? [];
  return PREPROCESS_PREVIEW_SAMPLE.filter((line) => focus.includes(line.kind)).map((line) => {
    const action = disposition(ruleKey, line.kind, cfg);
    const result = transform(ruleKey, cfg, line.text);
    const chips: string[] = [];
    if (action === 'KEEP') {
      chips.push('保留·只统计');
    } else if (action === 'MARK') {
      chips.push(`仅标记·${line.label}`);
    } else if (action === 'EXCLUDE') {
      chips.push(`剔除·${line.label}`);
    }
    chips.push(...result.flags);
    const shown = result.text.replace(/[ ]{2,}/g, ' ').trim();
    return {
      label: line.label,
      focused: true,
      dropped: action === 'EXCLUDE',
      changed: result.flags.length > 0,
      raw: preprocessSegments(line.text),
      out: preprocessSegments(shown || '（整行被规则移除）'),
      chips,
    };
  });
}

/** 影响计数：剔除几行 / 仅标记几行 / 改写几行 */
export function preprocessPreviewImpact(ruleKey: string, cfg: PreprocessConfig): PreviewImpact {
  let excluded = 0;
  let marked = 0;
  let changed = 0;
  preprocessPreviewRows(ruleKey, cfg).forEach((row) => {
    if (row.dropped) {
      excluded += 1;
    } else if (row.chips.includes(`仅标记·${row.label}`)) {
      marked += 1;
    }
    if (row.changed) {
      changed += 1;
    }
  });
  return { excluded, marked, changed };
}

/** 一句话结论（表格展开处显示，替代整段说明文字） */
export function preprocessPreviewSummary(ruleKey: string, cfg: PreprocessConfig): string {
  const impact = preprocessPreviewImpact(ruleKey, cfg);
  const tocLines = PREPROCESS_PREVIEW_SAMPLE.filter((line) => line.kind === 'toc').length;
  if (ruleKey === 'toc') {
    if (!tocRecognized(cfg)) {
      return `阈值下示例的 ${tocLines} 行目录不算目录页：按正文处理（阈值对三种处置都生效）`;
    }
    const action = preprocessAction('toc', cfg.rules.toc);
    if (action === 'EXCLUDE') {
      return `命中目录 ${tocLines} 行：不进检索内容流，也不进切片；展示视图仍完整`;
    }
    return action === 'MARK'
      ? `命中目录 ${tocLines} 行：加状态「仅标记·目录」，仍会被切片带走`
      : `命中目录 ${tocLines} 行：只统计，不改动内容`;
  }
  if (ruleKey === 'repeat') {
    const action = preprocessAction('repeat', cfg.rules.repeat);
    if (action === 'EXCLUDE') {
      return '重复份只保留首份，不进切片；统计里计入「重复」';
    }
    return action === 'MARK'
      ? '重复份加状态「仅标记·重复份」，仍进切片；统计里计入「仅标记」'
      : '重复份不改动：两份都进切片，只统计';
  }
  if (ruleKey === 'headerFooter') {
    const action = preprocessAction('headerFooter', cfg.rules.headerFooter);
    if (action === 'EXCLUDE') {
      return `页眉页脚 ${impact.excluded} 项不进检索内容流（含页码文本），也不进切片`;
    }
    return action === 'MARK'
      ? `页眉页脚 ${impact.marked} 项加状态「仅标记」，仍会被切片带走`
      : '页眉页脚只统计，不改动内容';
  }
  if (ruleKey === 'noise') {
    const action = preprocessAction('noise', cfg.rules.noise);
    if (action === 'EXCLUDE') {
      return '噪声页元素不进检索内容流，也不进切片';
    }
    return action === 'MARK'
      ? '噪声页元素加状态「仅标记·噪声」，仍进切片'
      : '噪声页元素只统计：计数来自组装环节，与本档位无关';
  }
  if (ruleKey === 'encoding') {
    return preprocessToggle('encoding', cfg.rules.encoding)
      ? `清理零宽、软连字符与全角字符（NFKC）：示例改写 ${impact.changed} 行`
      : '未清理：结果与原文逐字一致';
  }
  if (ruleKey === 'tidy') {
    return preprocessToggle('tidy', cfg.rules.tidy)
      ? `按子开关逐项改写 ${impact.changed} 行；只动文本不动结构`
      : '整条规则关闭：结果与原文逐字一致';
  }
  if (ruleKey === 'field') {
    if (!preprocessToggle('field', cfg.rules.field)) {
      return '整条规则关闭：不产字段、不改写检索文本';
    }
    return '改写金额 / 日期 / 面积并提取证号；2026/2/30 不存在，保持原文并记一条人工复核';
  }
  return cfg.custom?.enabled === 'OFF'
    ? '整组未执行：结果与原文逐字一致'
    : '命中 2 条规则：移除「版权所有©…」整段、电话号替换为 电话：***';
}

/** 规则当前值（配置区左列与展开标题共用） */
export function preprocessRuleValue(ruleKey: string, cfg: PreprocessConfig): string {
  if (ruleKey === 'custom') {
    const rules = Array.isArray(cfg.custom?.rules) ? cfg.custom.rules.length : 0;
    return cfg.custom?.enabled === 'OFF' ? '关' : `${rules} 条`;
  }
  if (ruleKey === 'field' || ruleKey === 'tidy' || ruleKey === 'encoding') {
    if (!preprocessToggle(ruleKey, cfg.rules[ruleKey])) {
      return '关';
    }
    const keys = Object.keys(cfg.rules[ruleKey].params ?? {});
    return keys.length && ruleKey !== 'encoding' ? `开 · ${keys.length} 项` : '开';
  }
  if (ruleKey === 'toc' && !tocRecognized(cfg)) {
    return '未判为目录';
  }
  const action = preprocessAction(ruleKey, cfg.rules[ruleKey]);
  return action === 'KEEP' ? '保留' : action === 'MARK' ? '标记' : '剔除';
}

/** 规则标题（含自定义规则组） */
export function preprocessRuleTitle(ruleKey: string): string {
  return PREPROCESS_RULE_LABELS[ruleKey] ?? ruleKey;
}
