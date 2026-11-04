<template>
  <div class="usage-pop">
    <div class="usage-pop-head">
      <span class="usage-pop-title">{{ title }}</span>
      <span class="usage-pop-total">{{ total }} tok</span>
    </div>
    <div v-if="model" class="usage-pop-row">
      <span>提供方 / 模型</span><span>{{ model }}</span>
    </div>
    <div class="usage-pop-row">
      <span>缓存命中</span><span>{{ cacheRate }}%</span>
    </div>
    <div class="usage-pop-row">
      <span>未缓存输入</span><span>{{ cacheMiss }} tok</span>
    </div>
    <div class="usage-pop-row">
      <span>缓存读取</span><span>{{ cacheHit }} tok</span>
    </div>
    <div class="usage-pop-row">
      <span>缓存写入</span><span>{{ cacheWrite }} tok</span>
    </div>
    <div class="usage-pop-row">
      <span>输出</span><span>{{ completion }} tok</span>
    </div>
  </div>
</template>

<script setup lang="ts">
// Token 用量明细：标题 + 总量 + 缓存命中/读写/输出分项
withDefaults(
  defineProps<{
    /** 弹层标题 */
    title: string;
    /** 总量展示文本（调用方决定是否千分位） */
    total: string;
    /** 提供方 / 模型；为空时不显示该行 */
    model?: string;
    /** 缓存命中率（百分比数值） */
    cacheRate: number;
    /** 未缓存输入 token 数 */
    cacheMiss: number;
    /** 缓存读取 token 数 */
    cacheHit: number;
    /** 输出 token 数 */
    completion: number;
    /** 缓存写入 token 数 */
    cacheWrite?: number;
  }>(),
  { model: '', cacheWrite: 0 },
);
</script>

<style scoped lang="css">
.usage-pop {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.usage-pop-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--kb-line);
}

.usage-pop-title {
  color: var(--kb-text-2);
  font-size: 12px;
  font-weight: 600;
}

.usage-pop-total {
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

.usage-pop-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.usage-pop-row > :last-child {
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
}
</style>
