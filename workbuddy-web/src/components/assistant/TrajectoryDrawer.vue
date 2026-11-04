<template>
  <el-drawer v-model="visible" title="会话轨迹" size="76%">
    <div class="trajectory-shell">
      <p v-if="trailTurns.length === 0" class="trajectory-empty">还没有活动。</p>
      <template v-else>
        <button
          v-if="hiddenTrailTurns > 0"
          class="turn-earlier"
          type="button"
          @click="showEarlierTrail"
        >
          显示更早的 {{ hiddenTrailTurns }} 轮
        </button>
        <div
          class="overview"
          @wheel.prevent="onOverviewWheel"
          @contextmenu.prevent="clearTrailView"
          @mousedown="onOverviewDown"
          @mousemove="onOverviewMove"
          @mouseup="onOverviewUp"
          @mouseleave="onOverviewUp"
        >
          <div class="overview-toolbar">
            <span class="overview-hint">滚轮缩放 · 拖选区间筛选 · 右键清除</span>
            <span class="overview-tools">
              <button class="overview-zoom" type="button" @click="zoomTrail(0.8)">＋</button>
              <button class="overview-zoom" type="button" @click="zoomTrail(1.25)">－</button>
              <button class="overview-zoom" type="button" @click="clearTrailView">重置</button>
            </span>
          </div>
          <div class="overview-ruler">
            <span class="overview-label"></span>
            <div class="overview-ruler-track">
              <span
                v-for="tick in rulerTicks"
                :key="tick.key"
                class="overview-tick"
                :style="{ left: tick.left }"
                >{{ tick.label }}</span
              >
            </div>
            <span class="overview-duration"></span>
          </div>
          <div v-for="turn in pagedTrailTurns" :key="turn.key" class="overview-row">
            <span class="overview-label">第 {{ turn.index }} 轮</span>
            <div class="overview-track">
              <div
                v-if="trailSelection"
                class="overview-band"
                :style="bandStyle(trailSelection)"
              ></div>
              <button
                v-for="node in turn.nodes.filter((item) => item.kind !== 'user')"
                :key="node.key"
                type="button"
                class="overview-span"
                :data-kind="node.kind"
                :style="spanStyle(node)"
                :title="`${node.badge} · ${node.title}`"
                @click="selectNode(node)"
              ></button>
            </div>
            <span class="overview-duration">{{ turn.duration }}</span>
          </div>
        </div>
        <div class="trajectory-main">
          <div class="traj-table" @scroll="onTrajScroll">
            <div class="traj-viewport" :style="{ height: `${trajTotal}px` }">
              <div class="traj-window" :style="{ transform: `translateY(${trajWindow.offset}px)` }">
                <template v-for="row in trajWindow.rows" :key="row.key">
                  <header v-if="row.node === null" class="traj-turn">
                    <span>第 {{ row.turn.index }} 轮</span>
                    <span class="traj-turn-time">{{ row.turn.startedAt }}</span>
                    <span class="traj-status" :data-status="row.turn.status">{{
                      row.turn.statusLabel
                    }}</span>
                    <span class="traj-turn-duration">{{ row.turn.duration }}</span>
                  </header>
                  <button
                    v-else
                    type="button"
                    class="traj-row"
                    :class="{ 'is-selected': selected?.key === row.node?.key }"
                    @click="selectTrajRow(row)"
                  >
                    <span class="traj-time">{{ row.node?.time }}</span>
                    <span class="traj-badge" :data-kind="row.node?.kind">{{
                      row.node?.badge
                    }}</span>
                    <span class="traj-title">{{ row.node?.title }}</span>
                    <span class="traj-duration">{{ row.node?.duration ?? '' }}</span>
                  </button>
                </template>
              </div>
            </div>
          </div>
          <aside class="traj-inspector">
            <template v-if="selected">
              <div class="inspector-head">
                <span class="traj-badge" :data-kind="selected.kind">{{ selected.badge }}</span>
                <span class="inspector-title">{{ selected.title }}</span>
              </div>
              <p class="inspector-meta">
                {{ selected.time }}
                <template v-if="selected.duration"> · 耗时 {{ selected.duration }}</template>
              </p>
              <p v-if="selected.meta" class="inspector-meta">{{ selected.meta }}</p>
              <pre v-if="selected.detail" class="inspector-body">{{ selected.detail }}</pre>
              <p v-else class="inspector-meta">没有更多细节。</p>
            </template>
            <p v-else class="trajectory-empty">选择左侧一条记录查看详情。</p>
          </aside>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, toRef } from 'vue';

import { useTrajectory } from '@/composables/useTrajectory';
import type { ModelCallItem, SessionEventItem } from '@/types/assistant';

const props = defineProps<{
  visible: boolean;
  events: SessionEventItem[];
  modelCalls: ModelCallItem[];
}>();
const emit = defineEmits<{ 'update:visible': [value: boolean] }>();

const visible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value),
});

const {
  trailTurns,
  selected,
  selectNode,
  trailSelection,
  spanStyle,
  bandStyle,
  pagedTrailTurns,
  hiddenTrailTurns,
  showEarlierTrail,
  rulerTicks,
  trajWindow,
  trajTotal,
  onTrajScroll,
  selectTrajRow,
  onOverviewWheel,
  onOverviewDown,
  onOverviewMove,
  onOverviewUp,
  zoomTrail,
  clearTrailView,
} = useTrajectory({
  events: toRef(props, 'events'),
  modelCalls: toRef(props, 'modelCalls'),
});
</script>

<style scoped lang="css">
/* 会话轨迹：顶部时间概览 + 按轮次的事件记录表 + 右侧检查器（对齐 DSH Trajectory） */
.trajectory-shell {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
}

.trajectory-empty {
  color: var(--kb-text-3);
  font-size: 13px;
}

.overview {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--kb-line);
  cursor: crosshair;
  user-select: none;
}

.overview-hint {
  margin: 0;
  color: var(--kb-text-3);
  font-size: 11px;
}

.overview-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.overview-tools {
  display: flex;
  gap: 4px;
}

.overview-zoom {
  padding: 0 6px;
  border: 1px solid var(--kb-line);
  border-radius: 5px;
  color: var(--kb-text-3);
  background: transparent;
  cursor: pointer;
  font-size: 11px;
  line-height: 18px;
}

.overview-zoom:hover {
  color: var(--kb-text-2);
  background: rgb(255 255 255 / 5%);
}

.overview-ruler {
  display: flex;
  gap: 8px;
  align-items: center;
}

.overview-ruler-track {
  position: relative;
  flex: 1;
  height: 14px;
}

.overview-tick {
  position: absolute;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 10px;
  transform: translateX(-50%);
}

.overview-band {
  position: absolute;
  top: 0;
  bottom: 0;
  border-radius: 999px;
  background: rgb(96 165 250 / 22%);
  pointer-events: none;
}

.overview-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.overview-label {
  flex: none;
  width: 56px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.overview-track {
  position: relative;
  flex: 1;
  height: 10px;
  border-radius: 999px;
  background: rgb(255 255 255 / 5%);
}

.overview-span {
  position: absolute;
  top: 0;
  bottom: 0;
  min-width: 3px;
  padding: 0;
  border: none;
  border-radius: 999px;
  background: var(--kb-text-3);
  cursor: pointer;
}

.overview-span[data-kind='tool'] {
  background: var(--kb-primary-2);
}

.overview-span[data-kind='call'] {
  background: #60a5fa;
}

.overview-span[data-kind='assistant'] {
  background: #34d399;
}

.overview-span[data-kind='error'] {
  background: var(--kb-danger);
}

.overview-duration {
  flex: none;
  width: 48px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: right;
}

.trajectory-main {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 14px;
  min-height: 0;
}

.traj-table {
  max-height: 62vh;
  overflow: auto;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
}

.traj-viewport {
  position: relative;
}

.traj-window {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
}

.traj-turn {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 10px;
  border-bottom: 1px solid var(--kb-line);
  background: var(--kb-surface);
  font-size: 12px;
}

.traj-turn-time,
.traj-turn-duration {
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.traj-status {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 999px;
  color: var(--kb-text-2);
  background: rgb(255 255 255 / 6%);
  font-size: 11px;
}

.traj-status[data-status='completed'] {
  color: var(--kb-ok);
}

.traj-status[data-status='aborted'],
.traj-status[data-status='error'] {
  color: var(--kb-danger);
}

.traj-status[data-status='pending_stop'] {
  color: #f59e0b;
}

.traj-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
  width: 100%;
  padding: 6px 10px;
  border: none;
  border-bottom: 1px solid rgb(255 255 255 / 4%);
  color: var(--kb-text);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-size: 12px;
}

.traj-row:hover {
  background: rgb(255 255 255 / 4%);
}

.traj-row.is-selected {
  background: rgb(96 165 250 / 12%);
}

.traj-time {
  flex: none;
  width: 62px;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.traj-badge {
  flex: none;
  width: 34px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.traj-badge[data-kind='tool'] {
  color: var(--kb-primary-2);
}

.traj-badge[data-kind='call'] {
  color: #60a5fa;
}

.traj-badge[data-kind='error'] {
  color: var(--kb-danger);
}

.traj-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.traj-duration {
  flex: none;
  width: 48px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: right;
}

.traj-inspector {
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
}

.inspector-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 6px;
}

.inspector-title {
  font-weight: 600;
  font-size: 13px;
}

.inspector-meta {
  margin: 2px 0;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.inspector-body {
  max-height: 55vh;
  margin: 8px 0 0;
  padding: 10px;
  overflow: auto;
  overflow-wrap: anywhere;
  border-radius: 8px;
  color: var(--kb-text-2);
  background: rgb(0 0 0 / 30%);
  font-size: 11px;
  white-space: pre-wrap;
}

.turn-earlier {
  align-self: center;
  padding: 4px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 999px;
  color: var(--kb-text-3);
  background: transparent;
  cursor: pointer;
  font-size: 11px;
}

.turn-earlier:hover {
  color: var(--kb-text-2);
  background: rgb(255 255 255 / 4%);
}
</style>
