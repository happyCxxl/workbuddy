<template>
  <!-- 模型：切换时自动带出维度 / 度量 / 窗口 / 批上限，避免手填与模型目录不一致 -->
  <div class="ef-row">
    <span class="ef-row-label">
      模型
      <i class="ef-help" :title="MODEL_HINT">?</i>
    </span>
    <el-select
      v-if="!readonly"
      :model-value="model"
      size="small"
      class="ef-select"
      @change="(value: string) => setModel(value)"
    >
      <el-option
        v-for="item in EMBED_MODELS"
        :key="item.key"
        :value="item.key"
        :label="item.enabled ? item.label : `${item.label} · 未启用`"
        :disabled="!item.enabled"
      />
    </el-select>
    <span v-else class="ef-row-value">{{ model || '—' }}</span>
  </div>

  <!-- 模型决定的指标：只读展示，用来印证「这些由模型决定」 -->
  <div class="ef-metrics">
    <span class="ef-metric"
      ><i class="ef-num">{{ catalog?.dimension ?? '—' }}</i> 维</span
    >
    <span class="ef-metric"
      ><i class="ef-num">{{ catalog?.metric ?? '—' }}</i></span
    >
    <span class="ef-metric"
      >窗口 <i class="ef-num">{{ catalog?.contextWindowTokens ?? '—' }}</i></span
    >
    <span class="ef-metric"
      >批上限 <i class="ef-num">{{ catalog?.batchLimit ?? '—' }}</i></span
    >
  </div>

  <!-- 模板：{content} / {query} 占位符 -->
  <div v-for="item in EMBED_TEMPLATES" :key="item.key" class="ef-row">
    <span class="ef-row-label">
      {{ item.label }}
      <i class="ef-help" :title="item.hint">?</i>
    </span>
    <el-input
      v-if="!readonly"
      :model-value="textOf(item.key)"
      size="small"
      class="ef-input"
      @update:model-value="(value: string) => setText(item.key, value)"
    />
    <span v-else class="ef-row-value ef-mono">{{ textOf(item.key) || '—' }}</span>
  </div>

  <!-- 开关 -->
  <div v-for="item in EMBED_TOGGLES" :key="item.key" class="ef-row">
    <span class="ef-row-label">
      {{ item.label }}
      <i class="ef-help" :title="item.hint">?</i>
    </span>
    <el-switch
      v-if="!readonly"
      :model-value="toggleOf(item.key)"
      size="small"
      @update:model-value="(value: string | number | boolean) => setToggle(item.key, value)"
    />
    <span v-else class="ef-row-value">{{ toggleOf(item.key) ? '开' : '关' }}</span>
  </div>

  <!-- 数值参数 -->
  <div class="ef-numbers">
    <label v-for="item in EMBED_NUMBERS" :key="item.key" class="ef-param">
      <span class="ef-param-label">{{ item.label }}</span>
      <el-input
        v-if="!readonly"
        :model-value="numberOf(item.key)"
        size="small"
        @update:model-value="(value: string) => setNumber(item.key, value)"
      />
      <span v-else class="ef-row-value">{{ numberOf(item.key) || '—' }}</span>
    </label>
  </div>
</template>

<script setup lang="ts">
/**
 * 向量化策略表单。
 *
 * <p>维度 / 度量 / 上下文窗口 / 批上限由**模型决定**，不由用户手填 ——
 * 后端按模型目录校验这几项。切换模型时一并写入，
 * 保证快照自洽（后端 `StaticModelCatalog` 是这些值的单一事实源）。
 *
 * <p>配置对象的读写都走显式 getter/setter：它是 `Record<string, unknown>`，
 * 动态取键会让每个属性都退化成 unknown，模板里到处要断言。
 */
import { computed } from 'vue';

import {
  EMBED_MODELS,
  EMBED_NUMBERS,
  EMBED_TEMPLATES,
  EMBED_TOGGLES,
  embedModelOf,
} from '@/types/strategy-config';
import type { EmbedConfig } from '@/types/strategy-config';

const config = defineModel<EmbedConfig>('config', { required: true });

const props = defineProps<{ readonly: boolean }>();

const MODEL_HINT = '模型决定向量维度与上下文窗口；未启用的模型不可选';

const catalog = computed(() => embedModelOf(config.value.model));

const model = computed(() => config.value.model ?? '');

const TEXT_KEYS: string[] = EMBED_TEMPLATES.map((item) => item.key);
const TOGGLE_KEYS: string[] = EMBED_TOGGLES.map((item) => item.key);
const NUMBER_KEYS: string[] = EMBED_NUMBERS.map((item) => item.key);

function readString(key: string): string {
  const value = config.value[key];
  return typeof value === 'string' ? value : '';
}

function readBool(key: string): boolean {
  return config.value[key] === 'ON';
}

function readNumber(key: string): string {
  const value = config.value[key];
  return typeof value === 'number' ? String(value) : '';
}

/** 模板类字段（docTemplate / queryTemplate） */
function textOf(key: string): string {
  return TEXT_KEYS.includes(key) ? readString(key) : '';
}

function setText(key: string, value: string): void {
  config.value[key] = value;
}

function toggleOf(key: string): boolean {
  return TOGGLE_KEYS.includes(key) ? readBool(key) : false;
}

function setToggle(key: string, value: string | number | boolean): void {
  config.value[key] = value === true ? 'ON' : 'OFF';
}

function numberOf(key: string): string {
  return NUMBER_KEYS.includes(key) ? readNumber(key) : '';
}

/** 数值字段按数字存：后端按数字校验，存成字符串会走到类型不匹配 */
function setNumber(key: string, value: string): void {
  const parsed = Number(value);
  config.value[key] = Number.isFinite(parsed) ? parsed : value;
}

/** 切换模型：连同模型决定的四项一起写入，保持快照与模型目录一致 */
function setModel(key: string): void {
  config.value.model = key;
  const item = embedModelOf(key);
  if (!item) {
    return;
  }
  config.value.dimension = item.dimension;
  config.value.metric = item.metric;
  config.value.contextWindowTokens = item.contextWindowTokens;
  config.value.batchLimit = item.batchLimit;
  config.value.normalized = true;
}

/** 编辑态首次挂载且没有模型时，用启用中的默认模型预填 */
const preferredModel = EMBED_MODELS.find((item) => item.enabled)?.key;
if (!props.readonly && !config.value.model && preferredModel) {
  setModel(preferredModel);
}
</script>

<style scoped lang="css">
.ef-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  min-height: 44px;
  border-bottom: 1px solid var(--kb-line);
}

.ef-row-label {
  display: flex;
  gap: 6px;
  align-items: center;
  color: var(--kb-text-2);
  font-size: 13px;
}

.ef-help {
  display: grid;
  width: 14px;
  height: 14px;
  border: 1px solid var(--kb-line-strong);
  border-radius: 50%;
  color: var(--kb-text-3);
  font-size: 9px;
  font-style: normal;
  cursor: help;
  place-items: center;
}

.ef-help:hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.ef-row-value {
  color: var(--kb-text-1);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.ef-mono {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  font-weight: 400;
}

.ef-select {
  width: 220px;
}

.ef-input {
  width: 220px;
}

.ef-metrics {
  display: flex;
  gap: 18px;
  padding: 9px 12px;
  margin: 10px 0 2px;
  border-radius: 10px;
  background: rgb(255 255 255 / 4%);
}

.ef-metric {
  color: var(--kb-text-3);
  font-size: 12px;
}

/* 数值强调用类而不是元素选择器（stylelint selector-max-type 禁止元素选择器） */
.ef-num {
  color: var(--kb-text-1);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  font-style: normal;
  font-weight: 600;
}

.ef-numbers {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding-top: 12px;
}

.ef-param {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.ef-param-label {
  color: var(--kb-text-3);
  font-size: 12px;
}
</style>
