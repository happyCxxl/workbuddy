<template>
  <!-- 四条路由：每条一个算法下拉 + 该算法的参数 -->
  <div v-for="route in ROUTES" :key="route" class="cf-route">
    <div class="cf-route-head">
      <span class="cf-route-name">{{ routeLabel(route) }}</span>
      <el-select
        v-if="!readonly"
        :model-value="algorithmOf(route)"
        size="small"
        class="cf-algo"
        @change="(value: string) => setAlgorithm(route, value)"
      >
        <el-option
          v-for="option in CHUNK_ROUTE_ALGORITHMS[route]"
          :key="option.key"
          :value="option.key"
          :label="option.supported ? option.label : `${option.label}（未上线：${option.reason}）`"
          :disabled="!option.supported"
        />
      </el-select>
      <span v-else class="cf-route-value">{{ algorithmName(route) }}</span>
    </div>

    <!-- 参数按算法定义动态渲染：不同算法参数不同 -->
    <div v-if="paramEntries(route).length > 0" class="cf-params">
      <label v-for="entry in paramEntries(route)" :key="entry[0]" class="cf-param">
        <span class="cf-param-label"
          >{{ entry[1].label }}（{{ entry[1].min }}~{{ entry[1].max }}）</span
        >
        <el-input
          v-if="!readonly"
          :model-value="paramValueOf(route, entry[0])"
          size="small"
          @update:model-value="(value: string) => setParam(route, entry[0], value)"
        />
        <span v-else class="cf-param-value">{{ paramValueOf(route, entry[0]) || '—' }}</span>
      </label>
    </div>
  </div>

  <!-- 流程开关 -->
  <div v-for="item in CHUNK_PIPELINE_TOGGLES" :key="item.key" class="cf-row">
    <span class="cf-row-label">
      {{ item.label }}
      <i class="cf-help" :title="item.hint">?</i>
    </span>
    <el-switch
      v-if="!readonly"
      :model-value="pipelineOn(item.key)"
      size="small"
      @update:model-value="(value: string | number | boolean) => setPipeline(item.key, value)"
    />
    <span v-else class="cf-row-value">{{ pipelineOn(item.key) ? '开' : '关' }}</span>
  </div>

  <!-- 互斥提示：后端会拒，这里提前告知，避免用户走到保存才失败 -->
  <div v-if="mutuallyExclusive" class="cf-warn">
    「表格并入正文流」与表格算法「表 + 引导段落」语义重复，不能同时启用。
    请二选一：把表格算法改成行级切片，或关闭这个开关。
  </div>

  <!-- 流程数值参数 -->
  <div class="cf-numbers">
    <label v-for="item in CHUNK_PIPELINE_NUMBERS" :key="item.key" class="cf-param">
      <span class="cf-param-label">{{ item.label }}</span>
      <el-input
        v-if="!readonly"
        :model-value="pipelineValueOf(item.key)"
        size="small"
        @update:model-value="(value: string) => setPipelineValue(item.key, value)"
      />
      <span v-else class="cf-param-value">{{ pipelineValueOf(item.key) || '—' }}</span>
    </label>
  </div>
</template>

<script setup lang="ts">
/**
 * 切片策略表单。
 *
 * <p>覆盖四路由（正文 / 表格 / 图片 / 超长兜底）的算法与参数，以及流程开关；参数标签带上允许范围（与后端 ChunkParam 同源）。
 *
 * <p>两处后端约束在这里体现：
 * <ol>
 *   <li>**未上线算法置灰**：后端枚举里 `supported=false` 的项在下拉里可见但不可选，
 *       并标注「未上线」；</li>
 *   <li>**互斥**：「表格并入正文流」与表格算法「表 + 引导段落」语义重复，
 *       同时启用时后端报 40001。这里给明确提示，而不是静默让保存失败。</li>
 * </ol>
 *
 * <p>配置的读写走显式 getter/setter：配置对象是 `Record<string, unknown>`，
 * 动态取键会让属性退化成 unknown。
 */
import { computed } from 'vue';

import {
  CHUNK_PIPELINE_NUMBERS,
  CHUNK_PIPELINE_TOGGLES,
  CHUNK_ROUTE_ALGORITHMS,
  CHUNK_ROUTE_LABELS,
  algorithmLabel,
  findAlgorithmOption,
} from '@/types/strategy-config';
import type {
  ChunkConfig,
  ChunkParamHint,
  ChunkRouteKey,
  ChunkRouteRaw,
} from '@/types/strategy-config';

const config = defineModel<ChunkConfig>('config', { required: true });

defineProps<{ readonly: boolean }>();

const ROUTES: ChunkRouteKey[] = ['body', 'table', 'image', 'fallback'];

/** 表格算法「表 + 引导段落」与流程开关「表格并入正文流」互斥 */
const EXCLUSIVE_TABLE_ALGORITHM = 'context-merged';

const TOGGLE_KEYS: string[] = CHUNK_PIPELINE_TOGGLES.map((item) => item.key);
const NUMBER_KEYS: string[] = CHUNK_PIPELINE_NUMBERS.map((item) => item.key);

function routeLabel(route: ChunkRouteKey): string {
  return CHUNK_ROUTE_LABELS[route];
}

function algorithmOf(route: ChunkRouteKey): string {
  const routeConfig = config.value[route];
  return typeof routeConfig?.algorithm === 'string' ? routeConfig.algorithm : '';
}

/** 未知算法回落到键名本身 */
function algorithmName(route: ChunkRouteKey): string {
  return algorithmLabel(route, algorithmOf(route));
}

/** 取路由配置对象，缺失时就地补一个，保证后续赋值有落点 */
function ensureRoute(route: ChunkRouteKey): ChunkRouteRaw {
  const created: ChunkRouteRaw = config.value[route] ?? {};
  config.value[route] = created;
  return created;
}

function setAlgorithm(route: ChunkRouteKey, value: string): void {
  ensureRoute(route).algorithm = value;
}

/** 当前算法声明的参数（[键, 中文名]）；未登记的算法返回空，避免乱渲染参数 */
function paramEntries(route: ChunkRouteKey): [string, ChunkParamHint][] {
  const option = findAlgorithmOption(route, algorithmOf(route));
  return option?.params ? Object.entries(option.params) : [];
}

function paramValueOf(route: ChunkRouteKey, key: string): string {
  const params = config.value[route]?.params;
  return params ? (params[key] ?? '') : '';
}

function setParam(route: ChunkRouteKey, key: string, value: string): void {
  const target = ensureRoute(route);
  if (!target.params) {
    target.params = {};
  }
  target.params[key] = value;
}

function pipelineOn(key: string): boolean {
  return TOGGLE_KEYS.includes(key) && config.value.pipeline?.[key] === 'ON';
}

function pipelineValueOf(key: string): string {
  return NUMBER_KEYS.includes(key) ? (config.value.pipeline?.[key] ?? '') : '';
}

function ensurePipeline(): Record<string, string> {
  if (!config.value.pipeline) {
    config.value.pipeline = {};
  }
  return config.value.pipeline;
}

function setPipeline(key: string, value: string | number | boolean): void {
  ensurePipeline()[key] = value === true ? 'ON' : 'OFF';
}

function setPipelineValue(key: string, value: string): void {
  ensurePipeline()[key] = value;
}

/**
 * 互斥状态：表格并入正文流 与 表格算法「表 + 引导段落」同时成立。
 *
 * <p>不做联动禁用：两处都禁会死锁（开关开着时选不了算法、算法选中时关不了开关），
 * 只给明确提示，由用户二选一。
 */
const mutuallyExclusive = computed(
  () =>
    config.value.pipeline?.tableInBodyFlow === 'ON' &&
    config.value.table?.algorithm === EXCLUSIVE_TABLE_ALGORITHM,
);
</script>

<style scoped lang="css">
.cf-route {
  padding: 10px 0;
  border-bottom: 1px solid var(--kb-line);
}

.cf-route-head {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
}

.cf-route-name {
  color: var(--kb-text-2);
  font-size: 13px;
  font-weight: 600;
}

.cf-algo {
  width: 220px;
}

.cf-route-value {
  color: var(--kb-text-1);
  font-size: 13px;
  font-weight: 600;
}

.cf-params {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding-top: 10px;
}

.cf-param {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.cf-param-label {
  color: var(--kb-text-3);
  font-size: 12px;
}

.cf-param-value {
  color: var(--kb-text-1);
  font-size: 13px;
  font-weight: 600;
}

.cf-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  min-height: 44px;
  border-bottom: 1px solid var(--kb-line);
}

.cf-row-label {
  display: flex;
  gap: 6px;
  align-items: center;
  color: var(--kb-text-2);
  font-size: 13px;
}

.cf-help {
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

.cf-help:hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.cf-row-value {
  color: var(--kb-text-1);
  font-size: 13px;
  font-weight: 600;
}

.cf-warn {
  padding: 9px 12px;
  margin-top: 10px;
  border: 1px solid rgb(251 191 36 / 28%);
  border-radius: 10px;
  background: rgb(251 191 36 / 8%);
  color: var(--kb-warn);
  font-size: 12px;
  line-height: 1.6;
}

.cf-numbers {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding-top: 12px;
}
</style>
