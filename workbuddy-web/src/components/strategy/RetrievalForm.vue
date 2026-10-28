<template>
  <!-- 召回通道 -->
  <div class="rf-row">
    <span class="rf-row-label">
      召回通道
      <i class="rf-help" :title="CHANNEL_HINT">?</i>
    </span>
    <el-select v-if="!readonly" v-model="channel" size="small" class="rf-select">
      <el-option
        v-for="item in RETRIEVAL_CHANNELS"
        :key="item.key"
        :value="item.key"
        :label="item.label"
      />
    </el-select>
    <span v-else class="rf-row-value">{{ channelLabel }}</span>
  </div>

  <!-- 融合：仅混合召回时才有意义 -->
  <div class="rf-row">
    <span class="rf-row-label">
      融合方式
      <i class="rf-help" :title="FUSION_HINT">?</i>
    </span>
    <el-select
      v-if="!readonly"
      v-model="fusionMode"
      size="small"
      class="rf-select"
      :disabled="channel !== 'HYBRID'"
    >
      <el-option
        v-for="item in RETRIEVAL_FUSION_MODES"
        :key="item.key"
        :value="item.key"
        :label="item.label"
      />
    </el-select>
    <span v-else class="rf-row-value">{{ fusionLabel }}</span>
  </div>

  <!-- RRF 参数：只在 RRF 融合时展示 -->
  <div v-if="fusionMode === 'RRF'" class="rf-params">
    <label class="rf-param">
      <span class="rf-param-label">RRF k</span>
      <el-input v-if="!readonly" v-model="rrfK" size="small" />
      <span v-else class="rf-row-value">{{ rrfK || '—' }}</span>
    </label>
    <label class="rf-param">
      <span class="rf-param-label">单通道召回上限</span>
      <el-input v-if="!readonly" v-model="perChannelLimit" size="small" />
      <span v-else class="rf-row-value">{{ perChannelLimit || '—' }}</span>
    </label>
  </div>

  <!-- 重排：目前只有「不重排」，做成下拉以便后端补实现时前端只加选项 -->
  <div class="rf-row">
    <span class="rf-row-label">
      重排
      <i class="rf-help" :title="RERANK_HINT">?</i>
    </span>
    <el-select v-if="!readonly" v-model="rerankMode" size="small" class="rf-select">
      <el-option
        v-for="item in RETRIEVAL_RERANK_MODES"
        :key="item.key"
        :value="item.key"
        :label="item.label"
      />
    </el-select>
    <span v-else class="rf-row-value">{{ rerankLabel }}</span>
  </div>

  <!-- 后处理 -->
  <div class="rf-row">
    <span class="rf-row-label">
      后处理
      <i class="rf-help" :title="POSTPROCESS_HINT">?</i>
    </span>
    <el-select v-if="!readonly" v-model="postprocessMode" size="small" class="rf-select">
      <el-option
        v-for="item in RETRIEVAL_POSTPROCESS_MODES"
        :key="item.key"
        :value="item.key"
        :label="item.label"
      />
    </el-select>
    <span v-else class="rf-row-value">{{ postprocessLabel }}</span>
  </div>

  <!-- TopK 与分数阈值 -->
  <div class="rf-params">
    <label class="rf-param">
      <span class="rf-param-label">返回条数 TopK</span>
      <el-input v-if="!readonly" v-model="topK" size="small" />
      <span v-else class="rf-row-value">{{ topK || '—' }}</span>
    </label>
    <label class="rf-param">
      <span class="rf-param-label">分数阈值</span>
      <el-input v-if="!readonly" v-model="scoreThreshold" size="small" />
      <span v-else class="rf-row-value">{{ scoreThreshold || '0' }}</span>
    </label>
  </div>
</template>

<script setup lang="ts">
/**
 * 检索策略表单。
 *
 * <p>后端的预留模式（重排、预处理）目前只实现了 `NONE`，做成下拉：
 * 后端补实现时前端只需往选项表里加一项。
 *
 * <p>配置读写走**可写 computed**：配置对象是 `Record<string, unknown>`，
 * 动态取键会让属性退化成 unknown；按字段逐个收窄后，取值与赋值在模板里都无需断言。
 */
import { computed } from 'vue';

import {
  RETRIEVAL_CHANNELS,
  RETRIEVAL_FUSION_MODES,
  RETRIEVAL_POSTPROCESS_MODES,
  RETRIEVAL_RERANK_MODES,
} from '@/types/strategy-config';
import type { RetrievalConfig } from '@/types/strategy-config';

const config = defineModel<RetrievalConfig>('config', { required: true });

const props = defineProps<{ readonly: boolean }>();

const CHANNEL_HINT = '决定从哪些通道召回：纯向量、纯全文、或两者混合';
const FUSION_HINT = '仅混合召回需要融合两路结果；RRF 按排名倒数求和，无需两路分数可比';
const RERANK_HINT = '对召回结果重排（当前未实现重排模型）';
const POSTPROCESS_HINT = '父片展开：命中子片时返回其父片全文';

/** 由嵌套对象承载的模式字段 */
type ModeSection = 'rerank' | 'postprocess';

const MODE_OPTIONS: Record<ModeSection, readonly { key: string; label: string }[]> = {
  rerank: RETRIEVAL_RERANK_MODES,
  postprocess: RETRIEVAL_POSTPROCESS_MODES,
};

const channel = computed<string>({
  get: () => config.value.channel ?? '',
  set: (value) => {
    config.value.channel = value;
  },
});

const channelLabel = computed(
  () => RETRIEVAL_CHANNELS.find((item) => item.key === channel.value)?.label ?? '—',
);

/** fusion 是嵌套对象，就地补一个再写，避免 setter 里判空分散 */
function ensureFusion(): NonNullable<RetrievalConfig['fusion']> {
  if (!config.value.fusion) {
    config.value.fusion = {};
  }
  return config.value.fusion;
}

const fusionMode = computed<string>({
  get: () => config.value.fusion?.mode ?? 'NONE',
  set: (value) => {
    ensureFusion().mode = value;
  },
});

const fusionLabel = computed(
  () => RETRIEVAL_FUSION_MODES.find((item) => item.key === fusionMode.value)?.label ?? '—',
);

/**
 * 数值字段的写入：能解析成数字就存数字，解析不出一律**保持原值不动**（忽略非法输入）。
 */
function writeNumber(
  target: 'rrfK' | 'perChannelLimit' | 'topK' | 'scoreThreshold',
  value: string,
): void {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return;
  }
  if (target === 'rrfK') {
    ensureFusion().rrfK = parsed;
    return;
  }
  if (target === 'perChannelLimit') {
    ensureFusion().perChannelLimit = parsed;
    return;
  }
  config.value[target] = parsed;
}

const rrfK = computed<string>({
  get: () =>
    typeof config.value.fusion?.rrfK === 'number' ? String(config.value.fusion.rrfK) : '',
  set: (value) => writeNumber('rrfK', value),
});

const perChannelLimit = computed<string>({
  get: () =>
    typeof config.value.fusion?.perChannelLimit === 'number'
      ? String(config.value.fusion.perChannelLimit)
      : '',
  set: (value) => writeNumber('perChannelLimit', value),
});

function modeOf(section: ModeSection): string {
  return config.value[section]?.mode ?? 'NONE';
}

function labelFor(section: ModeSection): string {
  return labelOf(MODE_OPTIONS[section], modeOf(section));
}

function labelOf(options: readonly { key: string; label: string }[], key: string): string {
  return options.find((item) => item.key === key)?.label ?? key;
}

function writeMode(section: ModeSection, value: string): void {
  config.value[section] = { ...config.value[section], mode: value };
}

const rerankMode = computed<string>({
  get: () => modeOf('rerank'),
  set: (value) => writeMode('rerank', value),
});

const rerankLabel = computed(() => labelFor('rerank'));

const postprocessMode = computed<string>({
  get: () => modeOf('postprocess'),
  set: (value) => writeMode('postprocess', value),
});

const postprocessLabel = computed(() => labelFor('postprocess'));

const topK = computed<string>({
  get: () => (typeof config.value.topK === 'number' ? String(config.value.topK) : ''),
  set: (value) => writeNumber('topK', value),
});

const scoreThreshold = computed<string>({
  get: () =>
    typeof config.value.scoreThreshold === 'number' ? String(config.value.scoreThreshold) : '',
  set: (value) => writeNumber('scoreThreshold', value),
});

/** 编辑态首次挂载且没有通道时，兜底成混合召回（与种子策略一致） */
if (!props.readonly && !config.value.channel) {
  channel.value = 'HYBRID';
}
</script>

<style scoped lang="css">
.rf-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  min-height: 44px;
  border-bottom: 1px solid var(--kb-line);
}

.rf-row-label {
  display: flex;
  gap: 6px;
  align-items: center;
  color: var(--kb-text-2);
  font-size: 13px;
}

.rf-help {
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

.rf-help:hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.rf-row-value {
  color: var(--kb-text-1);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.rf-select {
  width: 240px;
}

.rf-params {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding: 10px 0;
  border-bottom: 1px solid var(--kb-line);
}

.rf-param {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.rf-param-label {
  color: var(--kb-text-3);
  font-size: 12px;
}
</style>
