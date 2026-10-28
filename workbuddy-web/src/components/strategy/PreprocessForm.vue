<template>
  <div class="pf-table">
    <div class="pf-head">
      <span>规则</span>
      <span>档位 / 开关</span>
      <span>影响</span>
      <span>效果</span>
    </div>

    <template v-for="key in PREPROCESS_RULE_ORDER" :key="key">
      <div class="pf-row" :class="{ 'is-open': openKey === key }">
        <span class="pf-name">{{ PREPROCESS_RULE_LABELS[key] }}</span>

        <span class="pf-ctl">
          <!-- 处置类：三选一（重复与另外三条同形） -->
          <el-radio-group
            v-if="isAction(key)"
            :model-value="actionOf(key)"
            size="small"
            :disabled="readonly"
            @update:model-value="
              (value: string | number | boolean | undefined) => setAction(key, value)
            "
          >
            <el-radio-button v-for="action in ACTIONS" :key="action" :value="action">
              {{ PREPROCESS_ACTION_LABELS[action] }}
            </el-radio-button>
          </el-radio-group>

          <!-- 开关类 -->
          <el-switch
            v-else-if="key !== 'custom'"
            :model-value="toggleOf(key)"
            size="small"
            :disabled="readonly"
            @update:model-value="(value: string | number | boolean) => setToggle(key, value)"
          />

          <!-- 自定义规则组：总开关 -->
          <el-switch
            v-else
            :model-value="customEnabled"
            size="small"
            :disabled="readonly"
            @update:model-value="(value: string | number | boolean) => setCustomEnabled(value)"
          />
        </span>

        <span class="pf-hint">{{ PREPROCESS_RULE_HINTS[key] }}</span>

        <el-button size="small" @click="toggleOpen(key)">
          {{ openKey === key ? '收起' : '看效果' }}
        </el-button>
      </div>

      <!-- 目录阈值：只在目录这一行下方展开，三种档位都生效 -->
      <div v-if="key === 'toc' && openKey === 'toc'" class="pf-params">
        <label v-for="param in PREPROCESS_TOC_PARAM_KEYS" :key="param" class="pf-param">
          <span class="pf-hint">{{ PREPROCESS_TOC_PARAM_LABELS[param] }}</span>
          <el-input
            :model-value="tocParamOf(param)"
            size="small"
            :disabled="readonly"
            @update:model-value="(value: string) => setTocParam(param, value)"
          />
        </label>
      </div>

      <!-- 子开关：折叠在所属规则行下方 -->
      <div v-if="subKeysOf(key).length && openKey === key" class="pf-sub">
        <div v-for="sub in subKeysOf(key)" :key="sub" class="pf-sub-row">
          <span>{{ subLabelOf(key, sub) }}</span>
          <el-switch
            :model-value="subToggleOf(key, sub)"
            size="small"
            :disabled="readonly"
            @update:model-value="
              (value: string | number | boolean) => setSubToggle(key, sub, value)
            "
          />
        </div>
      </div>

      <!-- 自定义规则列表：一行一条 + 分页，不撑长页面 -->
      <div v-if="key === 'custom' && openKey === 'custom'" class="pf-cust">
        <div v-for="(item, index) in pagedCustomRules" :key="index" class="pf-cust-row">
          <span class="pf-kind">{{ customPage * CUSTOM_PAGE_SIZE + index + 1 }}</span>
          <el-input
            :model-value="item.pattern"
            size="small"
            :disabled="readonly"
            placeholder="Java 正则，≤200 字符"
            @update:model-value="(value: string) => setCustomField(index, 'pattern', value)"
          />
          <el-radio-group
            :model-value="item.action"
            size="small"
            :disabled="readonly"
            @update:model-value="
              (value: string | number | boolean | undefined) =>
                setCustomField(index, 'action', String(value))
            "
          >
            <el-radio-button
              v-for="action in PREPROCESS_CUSTOM_ACTIONS"
              :key="action.key"
              :value="action.key"
            >
              {{ action.label }}
            </el-radio-button>
          </el-radio-group>
          <el-input
            :model-value="item.replacement ?? ''"
            size="small"
            :disabled="readonly || item.action !== 'REPLACE'"
            :placeholder="item.action === 'REPLACE' ? '替换文本，支持 $1' : '该动作不可填'"
            @update:model-value="(value: string) => setCustomField(index, 'replacement', value)"
          />
          <el-button size="small" text :disabled="readonly" @click="removeCustomRule(index)">
            删除
          </el-button>
        </div>
        <div v-if="!customRules.length" class="pf-empty">还没有自定义规则：链尾按列表顺序执行</div>
        <div class="pf-cust-foot">
          <span class="pf-hint">
            共 {{ customRules.length }} / {{ PREPROCESS_CUSTOM_RULE_MAX }} 条 · 第
            {{ customPage + 1 }} / {{ customPages }} 页
          </span>
          <span class="pf-actions">
            <el-button size="small" :disabled="customPage === 0" @click="customPage -= 1"
              >上一页</el-button
            >
            <el-button
              size="small"
              :disabled="customPage >= customPages - 1"
              @click="customPage += 1"
            >
              下一页
            </el-button>
            <el-button size="small" type="primary" :disabled="readonly" @click="addCustomRule">
              新增一条
            </el-button>
          </span>
        </div>
      </div>

      <!-- 效果：原文示例与当前选择的结果两个片段并列 -->
      <div v-if="openKey === key" class="pf-open">
        <div class="pf-frags">
          <div class="pf-frag">
            <div class="pf-frag-head">
              <span>原文示例</span>
              <span class="page-spacer"></span>
              <span>XX项目招标文件（节选）· 不可见字符已标出</span>
            </div>
            <div class="pf-frag-body">
              <div
                v-for="(row, index) in previewRows"
                :key="index"
                class="pf-line"
                :class="{ 'is-focus': row.focused }"
              >
                <span class="pf-kind">{{ row.label }}</span>
                <span class="pf-text">
                  <template v-for="(segment, position) in row.raw" :key="position">
                    <span v-if="segment.mark" class="pf-invisible">{{ segment.mark }}</span>
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </span>
              </div>
            </div>
          </div>

          <div class="pf-frag">
            <div class="pf-frag-head">
              <span>当前选择的结果</span>
              <span class="page-spacer"></span>
              <span>{{ ruleValue }}</span>
            </div>
            <div class="pf-frag-body">
              <div
                v-for="(row, index) in previewRows"
                :key="index"
                class="pf-line"
                :class="{
                  'is-focus': row.focused,
                  'is-dropped': row.dropped,
                  'is-changed': row.changed,
                }"
              >
                <span class="pf-kind">{{ row.label }}</span>
                <span class="pf-text">
                  <template v-for="(segment, position) in row.out" :key="position">
                    <span v-if="segment.mark" class="pf-invisible">{{ segment.mark }}</span>
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </span>
                <span class="pf-chips">
                  <span
                    v-for="(chip, position) in row.chips"
                    :key="position"
                    class="pf-chip"
                    :class="{
                      'is-mark': chip.startsWith('仅标记') || chip.startsWith('保'),
                      'is-out': chip.startsWith('剔除'),
                    }"
                  >
                    {{ chip }}
                  </span>
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="pf-impact">
          <span v-if="impact.excluded" class="pf-chip is-out">剔除 {{ impact.excluded }} 行</span>
          <span v-if="impact.marked" class="pf-chip is-mark">仅标记 {{ impact.marked }} 行</span>
          <span v-if="impact.changed" class="pf-chip is-mark">改写 {{ impact.changed }} 行</span>
          <span class="pf-hint">{{ previewSummary }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 预处理策略表单：一张表列全部规则（档位 / 开关就地可改），点「看效果」在该规则行下方
 * 展开「原文示例 + 当前选择的结果」两个片段（只体现这一条规则，差异可直接归因）。
 *
 * <p>配置对象**就地改**：只动自己负责的键，其余键原样保留（后端保存时会做归一化与校验，
 * 前端丢掉不认识的键会抹掉后端写入的字段）。旧开关格式（`repeat` 的 `enabled`）在写 `action`
 * 时一并清掉，避免同一规则留下两份口径而被保存校验拦下。
 */
import { computed, ref } from 'vue';

import {
  PREPROCESS_ACTION_LABELS,
  PREPROCESS_CUSTOM_ACTIONS,
  PREPROCESS_CUSTOM_RULE_MAX,
  PREPROCESS_FIELD_KEYS,
  PREPROCESS_FIELD_LABELS,
  PREPROCESS_RULE_HINTS,
  PREPROCESS_RULE_LABELS,
  PREPROCESS_RULE_ORDER,
  PREPROCESS_TIDY_KEYS,
  PREPROCESS_TIDY_LABELS,
  PREPROCESS_TOC_PARAM_KEYS,
  PREPROCESS_TOC_PARAM_LABELS,
  preprocessAction,
  preprocessSubToggle,
  preprocessToggle,
} from '@/types/strategy-config';
import type {
  PreprocessAction,
  PreprocessConfig,
  PreprocessRuleRaw,
} from '@/types/strategy-config';
import {
  preprocessPreviewImpact,
  preprocessPreviewRows,
  preprocessPreviewSummary,
  preprocessRuleValue,
} from '@/utils/preprocess-preview';

const config = defineModel<PreprocessConfig>('config', { required: true });

defineProps<{ readonly: boolean }>();

/** 自定义规则每页条数（超出用翻页，不出现滚动条） */
const CUSTOM_PAGE_SIZE = 5;

const ACTIONS: PreprocessAction[] = ['KEEP', 'MARK', 'EXCLUDE'];

const openKey = ref<string | null>('repeat');
const customPage = ref(0);

/** 取规则对象，缺失时就地补一个，保证后续赋值有落点 */
function ruleOf(key: string): PreprocessRuleRaw {
  if (!Object.hasOwn(config.value.rules, key)) {
    config.value.rules[key] = {};
  }
  return config.value.rules[key];
}

function isAction(key: string): boolean {
  return key === 'headerFooter' || key === 'toc' || key === 'noise' || key === 'repeat';
}

function actionOf(key: string): PreprocessAction {
  return preprocessAction(key, config.value.rules[key]);
}

function setAction(key: string, value: string | number | boolean | undefined): void {
  const rule = ruleOf(key);
  rule.action = String(value);
  // 旧开关格式清掉：同一规则只留 action 一份口径
  delete rule.enabled;
}

function toggleOf(key: string): boolean {
  return preprocessToggle(key, config.value.rules[key]);
}

function setToggle(key: string, value: string | number | boolean | undefined): void {
  ruleOf(key).enabled = value === true ? 'ON' : 'OFF';
}

function subToggleOf(key: string, param: string): boolean {
  return preprocessSubToggle(config.value.rules[key], param);
}

function setSubToggle(
  key: string,
  param: string,
  value: string | number | boolean | undefined,
): void {
  const rule = ruleOf(key);
  if (!rule.params) {
    rule.params = {};
  }
  rule.params[param] = value === true ? 'ON' : 'OFF';
}

function subKeysOf(key: string): string[] {
  if (key === 'tidy') {
    return [...PREPROCESS_TIDY_KEYS];
  }
  if (key === 'field') {
    return [...PREPROCESS_FIELD_KEYS];
  }
  return [];
}

function subLabelOf(key: string, param: string): string {
  if (key === 'tidy') {
    return PREPROCESS_TIDY_LABELS[param] ?? param;
  }
  return PREPROCESS_FIELD_LABELS[param] ?? param;
}

function tocParamOf(param: string): string {
  return config.value.rules.toc.params?.[param] ?? '';
}

function setTocParam(param: string, value: string): void {
  const rule = ruleOf('toc');
  if (!rule.params) {
    rule.params = {};
  }
  rule.params[param] = value;
}

/** 自定义规则组：缺失时就地补结构（enabled 默认 ON，与后端一致） */
function customGroup(): {
  enabled: string;
  rules: { pattern: string; action: string; replacement?: string }[];
} {
  if (!config.value.custom) {
    config.value.custom = { enabled: 'ON', rules: [] };
  }
  const group = config.value.custom as {
    enabled?: string;
    rules?: { pattern: string; action: string; replacement?: string }[];
  };
  if (!Array.isArray(group.rules)) {
    group.rules = [];
  }
  return group as {
    enabled: string;
    rules: { pattern: string; action: string; replacement?: string }[];
  };
}

const customEnabled = computed(() => customGroup().enabled !== 'OFF');

function setCustomEnabled(value: string | number | boolean): void {
  customGroup().enabled = value === true ? 'ON' : 'OFF';
}

const customRules = computed(() => customGroup().rules);

const customPages = computed(() =>
  Math.max(1, Math.ceil(customRules.value.length / CUSTOM_PAGE_SIZE)),
);

const pagedCustomRules = computed(() =>
  customRules.value.slice(
    customPage.value * CUSTOM_PAGE_SIZE,
    (customPage.value + 1) * CUSTOM_PAGE_SIZE,
  ),
);

function addCustomRule(): void {
  if (customRules.value.length >= PREPROCESS_CUSTOM_RULE_MAX) {
    return;
  }
  customRules.value.push({ pattern: '', action: 'REMOVE' });
  customPage.value = customPages.value - 1;
}

function removeCustomRule(index: number): void {
  customRules.value.splice(customPage.value * CUSTOM_PAGE_SIZE + index, 1);
}

function setCustomField(
  index: number,
  field: 'pattern' | 'action' | 'replacement',
  value: string,
): void {
  const target = customRules.value[customPage.value * CUSTOM_PAGE_SIZE + index];
  if (field === 'replacement') {
    if (value) {
      target.replacement = value;
    } else {
      delete target.replacement;
    }
    return;
  }
  if (field === 'action') {
    target.action = value;
    if (value !== 'REPLACE') {
      delete target.replacement;
    }
    return;
  }
  target.pattern = value;
}

function toggleOpen(key: string): void {
  openKey.value = openKey.value === key ? null : key;
}

const ruleValue = computed(() =>
  openKey.value ? preprocessRuleValue(openKey.value, config.value) : '',
);

const previewRows = computed(() =>
  openKey.value ? preprocessPreviewRows(openKey.value, config.value) : [],
);

const impact = computed(() =>
  openKey.value
    ? preprocessPreviewImpact(openKey.value, config.value)
    : { excluded: 0, marked: 0, changed: 0 },
);

const previewSummary = computed(() =>
  openKey.value ? preprocessPreviewSummary(openKey.value, config.value) : '',
);
</script>

<style scoped lang="css">
/* 规则表：一行一条，展开的预览直接长在该行下方 */
.pf-table {
  border: 1px solid var(--kb-line);
  border-radius: var(--kb-radius);
  overflow: hidden;
}

.pf-head,
.pf-row {
  display: grid;
  gap: 12px;
  align-items: center;
  padding: 9px 14px;
  border-bottom: 1px solid var(--kb-line);
  grid-template-columns: 150px 262px minmax(0, 1fr) 84px;
}

.pf-head {
  background: rgb(255 255 255 / 3%);
  color: var(--kb-text-3);
  font-size: 11px;
}

.pf-row {
  color: var(--kb-text-2);
  font-size: 13px;
}

.pf-row.is-open {
  background: rgb(52 211 153 / 6%);
}

.pf-name {
  color: var(--kb-text-1);
  font-weight: 600;
}

.pf-ctl {
  display: flex;
  align-items: center;
}

.pf-hint {
  color: var(--kb-text-3);
  font-size: 12px;
}

.pf-actions {
  display: flex;
  gap: 8px;
}

/* 子开关与阈值：跟在所属规则行下方，缩进一级 */
.pf-sub,
.pf-params {
  display: grid;
  gap: 0 18px;
  padding: 8px 14px 10px 176px;
  border-bottom: 1px solid var(--kb-line);
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.pf-params {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.pf-sub-row {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  color: var(--kb-text-2);
  font-size: 12px;
}

.pf-param {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* 自定义规则：一行一条 + 分页 */
.pf-cust {
  padding: 8px 14px 10px 176px;
  border-bottom: 1px solid var(--kb-line);
}

.pf-cust-row {
  display: grid;
  gap: 8px;
  align-items: center;
  padding: 5px 0;
  grid-template-columns: 20px minmax(0, 1fr) 152px 158px 58px;
}

.pf-cust-foot {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
}

.pf-empty {
  padding: 6px 0;
  color: var(--kb-text-3);
  font-size: 12px;
}

/* 效果：原文与结果两个片段并列 */
.pf-open {
  padding: 10px 14px 12px;
  border-bottom: 1px solid var(--kb-line);
  background: rgb(255 255 255 / 1.5%);
}

.pf-frags {
  display: grid;
  gap: 10px;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}

.pf-frag {
  border: 1px solid var(--kb-line);
  border-radius: 10px;
  overflow: hidden;
}

.pf-frag-head {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px 10px;
  border-bottom: 1px solid var(--kb-line);
  color: var(--kb-text-3);
  font-size: 12px;
}

.pf-frag-body {
  padding: 4px 8px 8px;
}

.pf-line {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding: 3px 6px;
  border-left: 2px solid transparent;
  border-radius: 4px;
  color: var(--kb-text-2);
  font-size: 12px;
  line-height: 1.6;
}

.pf-line.is-focus {
  border-left-color: var(--kb-primary);
  background: rgb(52 211 153 / 6%);
  color: var(--kb-text-1);
}

.pf-line.is-changed {
  background: rgb(52 211 153 / 10%);
}

.pf-text {
  flex: 1 1 auto;
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.pf-line.is-dropped .pf-text {
  color: var(--kb-text-4);
  text-decoration: line-through;
}

.pf-kind {
  flex: none;
  width: 46px;
  color: var(--kb-text-4);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
}

/* 原文里的不可见字符：标出来才看得出清理前后的差别 */
.pf-invisible {
  padding: 0 4px;
  border: 1px dashed var(--kb-line-strong);
  border-radius: 4px;
  color: var(--kb-text-4);
  font-size: 10px;
}

.pf-chips {
  display: flex;
  flex: 0 1 auto;
  flex-wrap: wrap;
  gap: 4px;
  justify-content: flex-end;
  max-width: 52%;
}

.pf-chip {
  flex: none;
  padding: 0 6px;
  border: 1px solid var(--kb-line-strong);
  border-radius: 9px;
  color: var(--kb-text-3);
  font-size: 11px;
  white-space: nowrap;
}

.pf-chip.is-mark {
  border-color: rgb(52 211 153 / 30%);
  color: var(--kb-primary);
}

.pf-chip.is-out {
  border-color: rgb(248 113 113 / 30%);
  color: var(--kb-danger);
}

.pf-impact {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
}
</style>
