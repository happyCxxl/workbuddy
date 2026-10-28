<template>
  <div
    class="chain-node"
    :class="[nodeClass, { 'is-fallback': detailIsFallback, 'is-failed': isFailed }]"
    :title="hoverTitle"
  >
    <!-- 连接桩：仅供后端血缘边路由用；用户不能拖拽连线（外层已禁用 connectable） -->
    <Handle type="target" :position="Position.Left" class="chain-handle" />
    <!-- 源桩按出度铺开：同一节点被多次用不同策略触发时，
         每条边从各自独立的桩出发，起点就不会叠在同一个点上 -->
    <Handle
      v-for="index in outCount"
      :id="`out-${index - 1}`"
      :key="index"
      type="source"
      :position="Position.Right"
      class="chain-handle chain-handle-out"
      :style="{ top: handleTop(index - 1) }"
    />
    <Handle v-if="outCount === 0" type="source" :position="Position.Right" class="chain-handle" />

    <span v-if="hit" class="tone-hit">命中策略</span>

    <!-- ① 状态行：状态色点 + 环节名 + 状态文字 -->
    <span class="chain-node-head">
      <i class="chain-dot" :class="toneClass"></i>
      <span class="chain-node-stage">{{ stageText }}</span>
      <span class="chain-node-status" :class="toneClass">{{ statusText }}</span>
    </span>

    <!-- ② 身份行：解析器版本 · 策略版本（组装环节是产物 schema 版本） -->
    <span class="chain-node-ident">{{ identText }}</span>

    <!--
      ③ 主体区：形态由环节配置给。
      bars = 指标行 + 构成图（解析）；checklist = 结构体检清单（组装，不再显示指标行）。
    -->
    <template v-if="bodyKind === 'checklist'">
      <span class="chain-node-check">
        <span v-for="item in checklist" :key="item.key" class="chain-node-check-row">
          <i class="chain-node-check-dot" :class="item.toneClass"></i>
          <span class="chain-node-check-label">{{ item.label }}</span>
          <span class="chain-node-check-value">{{ item.value }}</span>
          <span class="chain-node-check-note">{{ item.note }}</span>
        </span>
      </span>
    </template>
    <template v-else>
      <!-- 指标行：页数 / 元素数 / 耗时（缺项显示 —） -->
      <span class="chain-node-metrics">
        <span v-for="item in metrics" :key="item.key" class="chain-node-metric">
          <span class="chain-node-metric-value">{{ item.value }}</span>
          {{ item.unit }}
        </span>
      </span>

      <!--
        构成图：一个类型一条（类型标签 + 浅槽 + 彩色条 + 右侧数字），条长 = 该类占全部元素的比例。
        标签与数字都是定宽右对齐列，四条才能对齐成一张表。
      -->
      <span class="chain-node-compose">
        <span v-for="bar in composeBars" :key="bar.type" class="chain-node-bar" :title="bar.title">
          <span class="chain-node-bar-label">{{ bar.title }}</span>
          <span class="chain-node-bar-track">
            <span
              class="chain-node-bar-fill"
              :class="bar.fillClass"
              :style="{ width: bar.width }"
            ></span>
          </span>
          <span class="chain-node-bar-value">{{ bar.value }}</span>
        </span>
      </span>
    </template>

    <!--
      ④ 结论行：与主体区用分隔线隔开，独立成一块。
      `⚠` 与文本是**并排的两个子项**：图标不进截断盒，截断盒里只有纯文本。
    -->
    <span class="chain-node-summary" :class="toneClass" :title="summaryTitle">
      <span v-if="isWarning" class="chain-node-warn">⚠</span>
      <span class="chain-node-summary-text">{{ summaryText }}</span>
    </span>

    <!-- ⑤ 操作行：时间 + 详情 + 触发下一环节（按钮常驻，只有可点与不可用两态） -->
    <span class="chain-node-foot">
      <span class="chain-node-time" :title="timeTitle">{{ timeText }}</span>
      <span class="chain-node-actions">
        <button
          class="chain-node-detail"
          type="button"
          @pointerdown.stop
          @click.stop="data.onDetail?.()"
        >
          详情
        </button>
        <!--
          不可用时的 title 就是禁用原因：存储类型不一致时要看得到"切回原存储后可继续"。
          可点时不挂 title（可点态没有原因要给），链图本身不产生多余提示。
        -->
        <button
          v-if="nextStage !== null"
          class="chain-node-more"
          type="button"
          :disabled="!triggerEnabled"
          :title="triggerEnabled ? undefined : triggerDisabledReason"
          @pointerdown.stop
          @click.stop="data.onTrigger?.()"
        >
          触发{{ nextStageText }}
        </button>
      </span>
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import type { NodeProps } from '@vue-flow/core';

import {
  PIPELINE_STAGES,
  capabilityText,
  isTaskPending,
  stageLabel,
  statusTone,
} from '@/types/pipeline';
import { stageViewOf } from '@/types/pipeline-stage-view';
import type { ChainNodeData, LineageNode, PipelineStage } from '@/types/pipeline';

// 执行树节点 = 一次运行。这里只负责「长什么样」，位置与选中由外层图组件控制。
// 注意：Vue Flow 要求自定义节点接收 NodeProps，自定义载荷放在 data 里
const props = defineProps<NodeProps<ChainNodeData>>();

/** 环节名画在节点上：这样节点被拖到任何位置，读图都不会认错环节 */
const stageText = computed(() => stageLabel(props.data.node.stage));

const tone = computed(() => statusTone(props.data.node.status));

/**
 * 状态色调类名。
 *
 * <p>写成「取值 → 字面类名」的表，而不是拼 `tone-${tone}`：样式检查脚本只认模板/样式里
 * 出现过的字面类名，拼接出来的会被判成"样式类未使用"。
 */
const TONE_CLASSES: Record<string, string> = {
  ok: 'tone-ok',
  partial: 'tone-partial',
  run: 'tone-run',
  wait: 'tone-wait',
  fail: 'tone-fail',
};

const toneClass = computed(() => TONE_CLASSES[tone.value] ?? 'tone-wait');

const isFailed = computed(() => tone.value === 'fail');
const isRunning = computed(() => tone.value === 'run');

/** 状态文字公共表：终态文字与环节无关；进行态按环节给（见下） */
const STATUS_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  PARTIAL_SUCCESS: '部分成功',
  FAILED: '失败',
  QUEUED: '排队中',
};

/** 当前环节的展示配置：未配置的环节给中性内容，不回落成别的环节的口径 */
const stageView = computed(() => stageViewOf(props.data.node.stage));

const statusText = computed(() => {
  const status = props.data.node.status;
  if (status === 'RUNNING') {
    return stageView.value.runningText;
  }
  return STATUS_LABELS[status] ?? status;
});

/**
 * 身份行：这份产物自身是什么（两段都取不到时留空，不显示占位符）。
 *
 * <p>解析环节 = 解析器版本 · 策略版本；组装环节 = 产物 schema 版本。
 * 上下游关系由链图的连线表达，不在卡片上重复。
 */
const identText = computed(() => {
  const node = props.data.node;
  if (node.stage === 'STRUCTURE') {
    return statText(node) ?? '';
  }
  const parts = [capabilityText(node.capability), node.strategyVersion].filter(
    (part): part is string => typeof part === 'string' && part !== '',
  );
  return parts.join(' · ');
});

/** 从组装统计里取产物 schema 版本 */
function statText(node: LineageNode): string | null {
  const stats = node.stats;
  if (stats === null || !('schemaVersion' in stats)) {
    return null;
  }
  const raw = stats.schemaVersion;
  return typeof raw === 'string' && raw !== '' ? raw : null;
}

/** 身份行是否是"两段都缺失"的兜底文案：样式上弱化，不冒充真实值 */
const detailIsFallback = computed(() => {
  const node = props.data.node;
  if (node.stage === 'STRUCTURE') {
    return statText(node) === null;
  }
  return !node.strategyVersion && capabilityText(node.capability) === null;
});

/** 指标行三项：口径由环节配置给（固定三项三格，缺失项显示 `—`，行高与列位不变） */
const metrics = computed(() => stageView.value.metrics(props.data.node));

/** 主体区形态：构成条（解析）或体检清单（组装） */
const bodyKind = computed(() => stageView.value.bodyKind);

/** 体检清单行：状态点颜色类名由脚本给（样式检查看不到计算属性给出的名字，故放全局） */
interface ComposeBar {
  type: string;
  title: string;
  value: string;
  width: string;
  /** 条色类名（由环节配置按段类型给，模板只做一次绑定） */
  fillClass: string;
}

/** 体检项：多带一个状态点类名 */
interface CheckRow {
  key: string;
  label: string;
  value: string;
  note: string;
  toneClass: string;
}

/** 状态点配色：类名写成字面量表，取值由 tone 决定 */
const CHECK_TONES: Record<string, string> = {
  ok: 'chain-node-check-ok',
  warn: 'chain-node-check-warn',
  bad: 'chain-node-check-bad',
  idle: 'chain-node-check-idle',
};

const checklist = computed<CheckRow[]>(() =>
  stageView.value.checklist(props.data.node).map((item) => ({
    key: item.key,
    label: item.label,
    value: item.value,
    note: item.note,
    toneClass: CHECK_TONES[item.tone] ?? 'chain-node-check-idle',
  })),
);

const composeBars = computed<ComposeBar[]>(() =>
  stageView.value.composeBars(props.data.node).map((bar) => ({
    type: bar.type,
    title: bar.label,
    value: bar.value,
    width: bar.width,
    fillClass: bar.fillClass,
  })),
);

/**
 * 警告行。
 *
 * <p>进行态只陈述正在做什么（页面上没有可用的进度计数，不编造页码）；
 * 其余状态一律用后端给的事实文案，失败态在后端文案缺失时回落 `errorMsg`。
 *
 * <p>**文案为空就留空**（行高固定，不给 `—` 之类的占位）：统计没到手时不编造结论，
 * "没有可陈述的内容"本身就是要如实显示的信息。
 */
const summaryText = computed(() => stageView.value.summary(props.data.node));

/** 该行是否是"有内容要处置"的告警：加 `⚠` 图标与告警色；「无异常」与进行态不加 */
const isWarning = computed(() => {
  const text = summaryText.value;
  return text !== '' && text !== '无异常';
});

/** 悬停提示：告警被截断时把完整文案给出来 */
const summaryTitle = computed(() => (isWarning.value ? summaryText.value : ''));

/**
 * 下游环节：解析之后是组装；已在链路末端的环节没有下一环。
 *
 * <p>「触发下一环节」按钮**常驻**（该环节确有下游就渲染），能不能点由
 * {@link triggerEnabled} 决定 —— 按钮不隐藏，卡片也不会因它出现或消失而变高变矮。
 */
const nextStage = computed<PipelineStage | null>(() => {
  const index = PIPELINE_STAGES.indexOf(props.data.node.stage as PipelineStage);
  if (index < 0 || index >= PIPELINE_STAGES.length - 1) {
    return null;
  }
  return PIPELINE_STAGES[index + 1];
});

const nextStageText = computed(() => (nextStage.value === null ? '' : stageLabel(nextStage.value)));

/** 本节点是否已产出产物：产物即分叉点，没有它就不能从这个环节往下走 */
const hasProduct = computed(() => Boolean(props.data.node.productId));

/** 该文件的数据源与当前启用的数据源是否不一致（不一致时下游环节不可触发） */
const storageBlocked = computed(() => props.data.storageBlockReason !== '');

/**
 * 产出本节点产物的运行是否已成功或部分成功。
 *
 * <p>判据用节点自身的任务状态（与后端的下游门禁同一口径）：任务失败但产物已落盘时，
 * 这份产物不能再作为下游的输入。
 */
const runConsumable = computed(
  () => props.data.node.status === 'SUCCESS' || props.data.node.status === 'PARTIAL_SUCCESS',
);

/**
 * 触发下一环节是否可点：**本节点有产物**、**数据源一致**、**产出运行已成功或部分成功**，三个条件并列。
 *
 * <p>不要求本节点是选中路径末端：点击用的是**这张卡自己的产物**作上游
 * （`onTrigger` 把本节点交出去），同环节多个产物各自成链，就是接口的分叉语义。
 */
const triggerEnabled = computed(
  () => hasProduct.value && !storageBlocked.value && runConsumable.value,
);

/**
 * 不可用原因（简短，挂在按钮 title 上）：按"用户下一步能做什么"分档。
 *
 * <p>数据源不一致时给这条专属原因：它是可修复的（把当前启用切回原数据源后即可继续），
 * 比"本次运行没有产物"更能说明下一步。运行未成功同样可修复：重跑上游环节。
 */
const triggerDisabledReason = computed(() => {
  if (triggerEnabled.value) {
    return '';
  }
  if (storageBlocked.value) {
    return props.data.storageBlockReason;
  }
  if (!hasProduct.value) {
    return isTaskPending(props.data.node.status) ? '任务完成后可用' : '本次运行没有产物';
  }
  return isTaskPending(props.data.node.status)
    ? '任务完成后可用'
    : '上游运行未成功，请重新触发上游环节';
});

const hit = computed(() => props.data.hit);

/** 该节点的出度（下游运行数）：决定铺几个源连接桩 */
const outCount = computed(() => Math.max(props.data.outCount, 0));

/** 源桩在节点右侧的纵向位置：按序号均布，避免所有边从同一点出发 */
function handleTop(index: number): string {
  const total = outCount.value;
  if (total <= 1) {
    return '50%';
  }
  // 在 22%~78% 之间均布，既不贴边角也不重叠
  const ratio = 0.22 + (0.56 * index) / (total - 1);
  return `${Math.round(ratio * 100)}%`;
}

/** 时间戳取到分钟（YYYY-MM-DD HH:mm，带年份） */
function formatClock(value: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '';
}

/** 时间戳取到秒（YYYY-MM-DD HH:mm:ss）：悬停提示里用精度换"能核对" */
function formatClockSeconds(value: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '';
}

/**
 * 操作行时间：完整年月日 + 时分。
 *
 * <p>**只显示一个时刻**：跑完的环节看结束时刻，进行中的环节看开始时刻（"从几点开始等的"）。
 * 两端都写要占到 250px 以上，操作行放不下「时间 + 详情 + 触发下一环节」三个元素。
 *
 * <p>完整起止（带秒）放悬停提示，需要核对时能拿到。
 */
const timeText = computed(() => {
  const node = props.data.node;
  if (isRunning.value) {
    return formatClock(node.startedAt) || '—';
  }
  return formatClock(node.finishedAt) || formatClock(node.startedAt) || '—';
});

/** 悬停提示：完整起止时间（带年月日与秒） */
const timeTitle = computed(() => {
  const node = props.data.node;
  const started = formatClockSeconds(node.startedAt);
  const finished = formatClockSeconds(node.finishedAt);
  if (started && finished) {
    return `${started} → ${finished}`;
  }
  return started || finished || '';
});

/** 悬停提示：构成图的类型名之外，把失败原因也放进整卡提示 */
const hoverTitle = computed(() => {
  const node = props.data.node;
  const parts = [stageText.value, statusText.value];
  if (node.errorMsg) {
    parts.push(node.errorMsg);
  }
  return parts.join(' · ');
});

const nodeClass = computed(() => ({
  'is-selected': props.data.onPath,
  'is-end': props.data.pathEnd,
  'is-fail': tone.value === 'fail',
  'is-run': tone.value === 'run',
}));

defineOptions({ name: 'ChainNode' });
</script>

<style scoped lang="css">
/*
 * 卡片尺寸 320×214：所有状态**恒等**——行数固定、缺项用 `—` 占位、
 * 「触发下一环节」不可用时置灰而不隐藏，内容再多也只裁剪不换行。
 *
 * <p>**宽度按构成图与操作行定**：构成图一行是「44px 类型标签 + 条 + 36px 数字」，
 * 操作行是「完整年月日时分 + 详情 + 触发向量化」，两处合计约需 276px，取 320px 留出余量
 * （时间文本与条都可省略，按钮与标签列永不收缩）。
 *
 * <p>**高度按六行各自钉死的行高之和定**：head 18 + ident 15 + metrics 16 + 构成图 46
 * + 警告行 42 + 操作行 28 = 165，加警告行 margin 11、行间距 2×5、上下内边距 10×2
 * 与上下边框 1×2 共 214px，余量 12px：中文回退字体（Microsoft YaHei）的行高比拉丁字体高，
 * 余量用于吸收跨浏览器的行盒差异。每一行都必须显式给行高 —— 放任默认值会让行盒随字体度量
 * 变高，多行累加后把操作行顶出卡片。
 *
 * <p>与 ChainGraph 的 NODE_WIDTH / NODE_ROW_HEIGHT 保持一致
 * （行距按固定高度算，不一致会互相压住）。
 */
.chain-node {
  position: relative;
  display: flex;
  box-sizing: border-box;
  width: 320px;
  height: 214px;
  flex-direction: column;
  gap: 2px;
  padding: 10px 11px;
  overflow: hidden;
  border: 1px solid var(--kb-line-strong);
  border-radius: 11px;
  background: var(--kb-bg-2);
  cursor: grab;
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}

.chain-node:active {
  cursor: grabbing;
}

/* 节点状态样式（选中 / 路径末端 / 失败 / 运行中 / 兜底文案）、状态点的 tone-* 色
   与构成图的条色都定义在全局 styles/chain-graph.css：这些类名由计算属性动态给出，
   scoped 样式里的声明会被「未使用」检查判为死代码 */

.chain-handle {
  width: 5px;
  height: 5px;
  border: none;
  background: var(--kb-line-strong);
  opacity: 0.9;
}

/* 多源桩：绝对定位在右边缘，按 handleTop 给出的百分比落位 */
.chain-handle-out {
  right: -3px;
  transform: translateY(-50%);
}

/* ==================== 卡片内容：六行 ==================== */

/* ① 状态行：状态点 + 环节名 + 状态文字（18px 行高：容下 13px 中文与 7px 状态点） */
.chain-node-head {
  display: flex;
  flex: none;
  gap: 7px;
  align-items: center;
  line-height: 18px;
}

/* 环节名是读图时最先认的东西，给最大字号；状态文字紧跟其后、同色系 */
.chain-node-stage {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  color: var(--kb-text-1);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 状态文字：颜色随状态（tone-* 见全局 chain-graph.css） */
.chain-node-status {
  flex: none;
  font-size: 11px;
  font-weight: 600;
}

/* ② 身份行：解析器版本 · 策略版本（单行省略） */
.chain-node-ident {
  display: block;
  overflow: hidden;
  flex: none;
  color: var(--kb-text-2);
  font-size: 11px;
  line-height: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 兜底的 `—`：弱化，不冒充真实值 */
.chain-node.is-fallback .chain-node-ident {
  color: var(--kb-text-3);
}

/* ③ 指标行：页数 / 元素数 / 耗时 */
.chain-node-metrics {
  display: flex;
  flex: none;
  gap: 12px;
  align-items: baseline;
  color: var(--kb-text-2);
  font-size: 11px;
  line-height: 15px;
}

/*
 * ③' 体检清单（组装）：一行 = 状态点 + 项目名 + 关键量 + 说明。
 *
 * <p>五行的行高与间距合计必须落在主体区可用高度内 —— 卡片总高固定 214px，
 * 行数变化会挤出滚动或压扁结论行。
 */
.chain-node-check {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 4px;
  color: var(--kb-text-2);
  font-size: 11px;
  line-height: 14px;
}

.chain-node-check-row {
  display: flex;
  gap: 6px;
  align-items: baseline;
  white-space: nowrap;
}

.chain-node-check-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  transform: translateY(-1px);
}

/* 项目名定宽：五行才对得齐（列宽够放 4 个汉字） */
.chain-node-check-label {
  flex: none;
  width: 54px;
  color: var(--kb-text-3);
}

.chain-node-check-value {
  flex: none;
  color: var(--kb-text-1);
  font-weight: 600;
}

/* 说明弱化并允许截断：它只是补充，不参与列对齐 */
.chain-node-check-note {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--kb-text-4);
  font-size: 10px;
  text-overflow: ellipsis;
}

.chain-node-metric {
  display: flex;
  gap: 3px;
  align-items: baseline;
  white-space: nowrap;
}

/* 数值用等宽 + 略亮色：位数变化不抖动，"标签"与"数字"分得开 */
.chain-node-metric-value {
  color: var(--kb-text-1);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  font-weight: 600;
}

/* ④ 构成图：四条固定高度，等分占满 46px 区间 —— 条数恒定，高度不随数据变 */
.chain-node-compose {
  display: flex;
  height: 46px;
  flex: none;
  flex-direction: column;
  justify-content: space-evenly;
}

.chain-node-bar {
  display: flex;
  flex: none;
  gap: 6px;
  align-items: center;
}

/* 条左侧的类型标签：定宽右对齐，"正文/表格/图片/页眉页脚"四行对齐成一列 */
.chain-node-bar-label {
  width: 44px;
  flex: none;
  overflow: hidden;
  color: var(--kb-text-3);
  font-size: 10px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 条位（轨道）：定高 + 浅色底，给出"满量程"参照，底色由全局 `.chain-graph` 规则给。
 *
 * <p>数据只由 `.chain-node-bar-fill` 表达：底色比它弱一档，占比小的行既能看出落在量程哪里，
 * 又不会把轨道本身读成一条数据条。
 */
.chain-node-bar-track {
  display: block;
  height: 4px;
  min-width: 0;
  flex: 1;
  border-radius: 2px;
}

.chain-node-bar-fill {
  display: block;
  height: 100%;
  border-radius: 2px;
}

/* 条右侧的数字：定宽右对齐，位数变化不推挤条长 */
.chain-node-bar-value {
  width: 36px;
  flex: none;
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 10px;
  text-align: right;
}

/*
 * ⑤ 警告行：与构成图用一条淡分隔线隔开，成为独立一块。
 *
 * <p>**上下都要留白，且取值的依据是"视觉"距离**：
 * <ul>
 *   <li>`margin-top: 11px` —— 构成图最后一行的**条只有 4px 高、居中在 14px 行盒里**，
 *       行盒看似到分隔线 8px，条到线其实只有 5px。补到 11px 后条到线约 8px；</li>
 *   <li>`padding-top: 6px` —— `padding` 落在边框内侧，专门管"线到文字"的呼吸，
 *       与 `margin`（管"线到构成图"）分工不同，两个都给才有上下留白。</li>
 * </ul>
 *
 * <p>高度 42px = border 1 + padding 6 + 两行 27，另有 margin 11 在盒外；
 * 两行封顶、超出省略，完整文案在 `title` 里。无告警时该行显示「无异常」，进行态与统计缺失时留空。
 */
.chain-node-summary {
  display: flex;
  box-sizing: border-box;
  height: 34px;
  flex: none;
  gap: 3px;
  overflow: hidden;
  margin-top: 11px;
  padding-top: 6px;
  border-top: 1px solid var(--kb-line);
  color: var(--kb-text-3);
  font-size: 11px;
  line-height: 14px;
}

/* 告警图标与文案同色（颜色由状态 tone 给出，见全局 chain-graph.css），不参与收缩 */
.chain-node-warn {
  flex: none;
}

/*
 * 文案两行封顶：`-webkit-box` + `line-clamp` 双写，Chromium 走前者、新标准走后者。
 *
 * <p>**盒里只放纯文本**：再塞一个行内子项（如告警图标）会让 Chromium 不画省略号，
 * 长文案被静默截断、看不出还有内容。
 */
.chain-node-summary-text {
  display: -webkit-box;
  min-width: 0;
  flex: 1;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

/*
 * ⑥ 操作行：时间在左，两个按钮在右（上边框与内容分开）。
 *
 * <p>行高钉在 14px：容器默认行高比按钮还高，不钉就把整卡白顶高一段。
 */
.chain-node-foot {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 5px;
  border-top: 1px solid var(--kb-line);
  line-height: 14px;
}

/*
 * 操作行时间：可收缩并可省略（`min-width: 0` + `flex: 0 1 auto`）。
 *
 * <p>显式声明收缩：行内是"时间 + 两个按钮"，右侧两个按钮 `flex: none` 固定占位，
 * 宽度不够时只压缩时间文本 —— 默认的 `min-width: auto` 一旦被浏览器按最小内容尺寸处理，
 * 被挤掉的就是按钮（卡片 `overflow: hidden` 会直接裁掉右边那个）。
 */
.chain-node-time {
  overflow: hidden;
  min-width: 0;
  flex: 0 1 auto;
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chain-node-actions {
  display: flex;
  flex: none;
  gap: 6px;
}

/* 详情：次要动作，描边小按钮（`white-space` 防窄处折行把操作行顶高） */
.chain-node-detail {
  padding: 3px 8px;
  border: 1px solid var(--kb-line-2);
  border-radius: 6px;
  background: none;
  color: var(--kb-text-2);

  /* 显式写完整字体栈而不是 `font-family: inherit`：项目规范要求字体声明以通用族结尾 */
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 10px;
  white-space: nowrap;
  cursor: pointer;
}

.chain-node-detail:disabled {
  border-color: var(--kb-line);
  color: var(--kb-text-4);
  cursor: not-allowed;
}

.chain-node-detail:hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

/* 键盘可达：卡片本身不聚焦，操作行两个按钮都是焦点目标 */
.chain-node-detail:focus-visible {
  outline: 2px solid var(--kb-primary-2);
  outline-offset: 2px;
}

/* 触发下一环节：卡片上唯一的动作，用整块主色（同样防折行） */
.chain-node-more {
  padding: 3px 9px;
  border: none;
  border-radius: 6px;
  background: var(--kb-primary);
  color: var(--kb-btn-text);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 10px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background 0.15s,
    box-shadow 0.15s;
}

.chain-node-more:hover {
  box-shadow: 0 0 12px rgb(52 211 153 / 45%);
}

/* 键盘可达：卡片本身不聚焦，按钮是焦点目标 */
.chain-node-more:focus-visible {
  outline: 2px solid var(--kb-primary-2);
  outline-offset: 2px;
}

/* 不可用态：占位但不着色，行动点不落在无效操作上 */
.chain-node-more:disabled {
  background: var(--kb-surface);
  color: var(--kb-text-4);
  cursor: not-allowed;
  box-shadow: none;
}
</style>
