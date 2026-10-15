<template>
  <!-- 用 div 而非 router-link：卡片内含操作按钮，把 button 嵌进 a 是无效 HTML
       （浏览器会重排 DOM）。点击时编程式跳转 -->
  <div
    class="kb-card"
    role="link"
    tabindex="0"
    title="查看该知识库的文件处理链"
    @click="openStages"
    @keydown.enter.prevent="openStages"
    @keydown.space.prevent="openStages"
  >
    <div class="kb-top">
      <span class="kb-cube">
        <svg
          class="kb-icon"
          width="15"
          height="15"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
        >
          <path d="M2.2 5.2 8 2l5.8 3.2v5.6L8 14 2.2 10.8V5.2Z" stroke-linejoin="round" />
        </svg>
      </span>
      <span class="kb-name">{{ kb.name }}</span>
      <span
        class="kb-tag"
        :class="{
          'kb-tag-ok': kb.status === KB_STATUS_ACTIVE,
          'kb-tag-mute': kb.status === KB_STATUS_DISABLED,
        }"
      >
        <span class="kb-tag-dot"></span>
        {{ statusText }}
      </span>
    </div>
    <p class="kb-desc">{{ kb.description || '暂无业务场景说明' }}</p>
    <div class="kb-meta">
      <div class="kb-meta-item">
        <span class="kb-meta-value">{{ kb.documentCount ?? 0 }}</span>
        <span class="kb-meta-label">文档</span>
      </div>
      <div class="kb-meta-item">
        <span class="kb-meta-value" :class="{ 'kb-meta-mute': !kb.publishedIndexVersion }">
          {{ kb.publishedIndexVersion ?? '未发布' }}
        </span>
        <span class="kb-meta-label">索引版本</span>
      </div>
      <div class="kb-meta-item">
        <span class="kb-meta-value">{{ timeText }}</span>
        <span class="kb-meta-label">更新</span>
      </div>
    </div>

    <!--
      策略逐条列举（预处理 → 切片 → 向量化，即实际处理顺序）。
      **不放进上面的数值格**：那是「三个等宽短值」的布局、每格约 74px，
      而策略串是标识符（13~30 字符，最长 hybrid-rrf-k60-top10-parent-v1
      在等宽 11px 下约 181px），塞进去必然截断 —— 截断后就认不出是哪个策略。
      每条独占一行、标签左值右，一行放得下 30 字符。
    -->
    <div class="kb-strategies">
      <div v-for="s in strategies" :key="s.type" class="kb-strategy">
        <span class="kb-strategy-k">{{ s.label }}</span>
        <span class="kb-strategy-v" :class="{ 'kb-strategy-mute': !s.text }">
          {{ s.text || fallbackStrategyText }}
        </span>
      </div>
    </div>
    <div class="kb-ops">
      <!-- 操作按钮必须 .stop：卡片整体可点，不阻止冒泡会连带跳转到处理链页 -->
      <button class="kb-op" type="button" @click.stop="emit('import', kb)">导入文档</button>
      <button class="kb-op" type="button" @click.stop="emit('update', kb)">编辑</button>
      <button class="kb-op" type="button" @click.stop="openIndex">索引与发布</button>
      <button class="kb-op" type="button" @click.stop="openRetrieval">评测</button>
      <button
        class="kb-op"
        :class="{ 'kb-op-warn': kb.status === KB_STATUS_ACTIVE }"
        type="button"
        @click.stop="emit('toggle', kb)"
      >
        {{ kb.status === KB_STATUS_ACTIVE ? '停用' : '启用' }}
      </button>
      <button class="kb-op kb-op-danger" type="button" @click.stop="emit('delete', kb)">
        删除
      </button>
      <span class="kb-op-date">{{ dateText }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';

import type { KnowledgeBase } from '@/types/knowledge-base';
import { KB_STATUS_ACTIVE, KB_STATUS_DISABLED } from '@/types/knowledge-base';
import { STRATEGY_TYPE_LABELS, STRATEGY_TYPES } from '@/types/strategy';
import { formatDate, formatTime } from '@/utils/date';

// 知识库卡片：名称、状态、关键指标与操作入口
const props = defineProps<{
  /** 知识库业务数据 */
  kb: KnowledgeBase;
}>();

const emit = defineEmits<{
  /** 编辑：带出当前卡片数据供页面填表 */
  update: [kb: KnowledgeBase];
  /** 导入文档：预选该知识库打开导入弹窗 */
  import: [kb: KnowledgeBase];
  /** 停用/启用：二次确认与接口调用由页面负责 */
  toggle: [kb: KnowledgeBase];
  /** 删除 */
  delete: [kb: KnowledgeBase];
}>();

const router = useRouter();

/** 点卡片（或回车/空格）进入该库的文件处理链页 */
function openStages(): void {
  void router.push(`/knowledge/${props.kb.id}/stages`);
}

/** 进入该库的索引与发布页（组合版本、发布与回退） */
function openIndex(): void {
  void router.push(`/knowledge/${props.kb.id}/index`);
}

/**
 * 进入该库的检索评测页（测试台检索 + 运行记录并排对比 + 规则选优发布）。
 */
function openRetrieval(): void {
  void router.push(`/knowledge/${props.kb.id}/retrieval`);
}

const statusText = computed(() => (props.kb.status === KB_STATUS_ACTIVE ? '已启用' : '已停用'));
const timeText = computed(() => formatTime(props.kb.updateTime ?? ''));
const dateText = computed(() => formatDate(props.kb.updateTime ?? ''));

/**
 * 三条绑定策略（按实际处理顺序：预处理 → 切片 → 向量化）。
 *
 * <p>顺序用 {@link STRATEGY_TYPES} 的声明顺序，而不是手写三个字面量 ——
 * 那个常量与后端 StrategyType 对齐，加类型时这里自动跟上。
 */
const strategies = computed(() =>
  STRATEGY_TYPES.map((type) => ({
    type,
    label: STRATEGY_TYPE_LABELS[type] ?? type,
    text:
      type === 'PREPROCESS'
        ? props.kb.preprocessStrategyVersion
        : type === 'CHUNK'
          ? props.kb.chunkStrategyVersion
          : props.kb.embedStrategyVersion,
  })),
);

/**
 * 策略缺失时的占位文案，要区分两种情况：
 * 绑定开关关着（测评模式不绑定）说"开关已关闭"，开关开着才是"未绑定"。
 */
const fallbackStrategyText = computed(() =>
  props.kb.strategyBindingEnabled === 1 ? '未绑定' : '已关闭',
);
</script>

<style scoped lang="css">
/* 整张卡片是进入环节页的入口（点击即跳转），故有指针与键盘可达性 */
.kb-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--kb-line);
  border-radius: var(--kb-radius);
  background: linear-gradient(180deg, rgb(255 255 255 / 3.5%), rgb(255 255 255 / 1.2%));
  color: inherit;
  text-decoration: none;
  cursor: pointer;
  transition:
    border-color 0.2s,
    box-shadow 0.2s,
    transform 0.2s;
}

.kb-card:hover {
  border-color: rgb(52 211 153 / 45%);
  box-shadow:
    0 14px 40px rgb(0 0 0 / 38%),
    0 0 24px rgb(52 211 153 / 8%);
  transform: translateY(-2px);
}

.kb-top {
  display: flex;
  gap: 11px;
  align-items: center;
}

.kb-cube {
  display: grid;
  flex: none;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid rgb(52 211 153 / 22%);
  border-radius: 11px;
  background: var(--kb-tint);
  color: var(--kb-primary);
}

/*
 * 库名：标题行里**唯一可缩**的一项。
 *
 * **不做省略号截断**：从名称长度上限上解决 ——
 * 名称上限（KB_NAME_MAX = 14）就是按这里的可用宽度倒推的 —— 380px 卡片下约 216px ÷ 15px/汉字
 * = 14 字，合法名称必然整行显示完整，既不缩也不换行。
 * 超出时（字体渲染比估算宽、或窗口窄于列宽下限）兜底是**换行成两行**：
 * `overflow-wrap: anywhere` 让超长英文串也能断行，且它参与 min-content 计算、不会撑破行。
 */
.kb-name {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 15px;
  font-weight: 600;
}

/*
 * 状态标签：**不许收缩、不许折行**。
 *
 * flex 的收缩量按各子项宽度比例分摊：库名很长时状态标签会被扣掉几十像素，
 * 「已启用」折成"已启 / 用"两行、把标题行撑高。
 * 状态标签给 flex: none，让库名那侧独自承担收缩（它有省略号）。
 */
.kb-tag {
  display: inline-flex;
  flex: none;
  gap: 6px;
  align-items: center;
  margin-left: auto;
  padding: 3px 10px;
  border: 1px solid;
  border-radius: 99px;
  white-space: nowrap;
  font-size: 12px;
}

.kb-tag-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentcolor;
  box-shadow: 0 0 8px currentcolor;
}

.kb-tag-ok {
  border-color: rgb(163 230 53 / 30%);
  background: rgb(163 230 53 / 7%);
  color: var(--kb-ok);
}

.kb-tag-mute {
  border-color: var(--kb-line-strong);
  background: rgb(255 255 255 / 3%);
  color: var(--kb-text-3);
}

.kb-desc {
  min-height: 32px;
  margin: 0;
  color: var(--kb-text-3);
  font-size: 12px;
  line-height: 1.5;
}

.kb-meta {
  display: flex;
  align-items: center;
  padding: 9px 0;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
  background: rgb(0 0 0 / 18%);
}

/*
 * 三个等宽格子（flex: 1 即 basis 0，天然三等分）。
 *
 * `min-width: 0` 不可省：flex 项的自动最小宽度是 min-content，而值是 `nowrap` 文本
 * （min-content = 整串宽度），格子的自动最小宽度会等于这个宽度 —— 于是值一旦超过格宽，
 * 撑破的不是省略号，而是整行（`.kb-meta-value` 上的 `text-overflow: ellipsis` 平时是摆设）。
 * 归零之后格子按三等分收缩，值才真正走省略号。
 *
 * 现值都很短（`v3` / `未发布` / `17:32`），仍显式给 `min-width: 0` 防回归。
 */
.kb-meta-item {
  position: relative;
  flex: 1;
  min-width: 0;
  text-align: center;
}

.kb-meta-item + .kb-meta-item::before {
  position: absolute;
  top: 20%;
  left: 0;
  width: 1px;
  height: 60%;
  background: var(--kb-line);
  content: '';
}

.kb-meta-value {
  display: block;
  overflow: hidden;
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 未发布索引：弱化显示，与真实版本号区分 */
.kb-meta-mute {
  color: var(--kb-text-3);
  font-weight: 400;
}

.kb-meta-label {
  display: block;
  margin-top: 2px;
  color: var(--kb-text-3);
  font-size: 11px;
}

/*
 * 策略逐条列举：标签左（定宽）、策略串右（占满剩余）。
 *
 * <p>用与 .kb-meta 同一套"描边容器"语言，但**不做等分栅格** —— 策略串长度差异大
 * （13~30 字符），等分必然截断最长的那个。左标签定宽 52px（最长「向量化」三个字），
 * 其余全给策略串：380px 卡片下约 236px 可用，够放 30 字符的 hybrid-rrf-k60-top10-parent-v1。
 */
.kb-strategies {
  margin-top: 8px;
  padding: 8px 11px;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
  background: rgb(0 0 0 / 18%);
}

.kb-strategy {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.kb-strategy + .kb-strategy {
  margin-top: 6px;
}

.kb-strategy-k {
  flex: none;
  width: 52px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.kb-strategy-v {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--kb-primary-2);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  font-weight: 600;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 未绑定 / 开关已关闭：弱化成普通文字，不用主色 */
.kb-strategy-mute {
  color: var(--kb-text-3);
  font-weight: 400;
}

.kb-ops {
  display: flex;
  gap: 4px;
  align-items: center;
  padding-top: 10px;
  border-top: 1px solid var(--kb-line);

  /*
   * 钉在卡片底部：网格行高有下限（340px），内容比它矮时余量堆在策略块与操作行之间。
   */
  margin-top: auto;

  /*
   * 不换行：六个操作按钮（导入/编辑/索引与发布/评测/停用/删除）在 380px 卡片下
   * 约 260px，加右侧日期仍放得下；窄窗口下允许略挤，不折行、不缩字号。
   * 网格的列宽下限是 380px（见页面 .kb-grid），这里不会真的溢出。
   */
  flex-wrap: nowrap;
  white-space: nowrap;
}

.kb-op {
  margin-right: 6px;
  padding: 0;
  border: none;
  background: none;
  color: var(--kb-text-2);
  font-size: 13px;
  text-decoration: none;
  cursor: pointer;
  transition: color 0.15s;

  /* 允许收缩：极端窄窗口下先缩按钮间距，而不是立刻折行 */
  flex: none;
}

.kb-op:hover {
  color: var(--kb-primary);
}

/* 停用：与删除同属"会改变可用性"的操作，但不用 danger（那是不可恢复的删除），
   用警示色；启用走普通色 */
.kb-op-warn:hover {
  color: var(--kb-warn);
}

.kb-op-danger:hover {
  color: var(--kb-danger);
}

.kb-op-date {
  margin-left: auto;
  color: var(--kb-text-3);
  font-size: 11px;
}
</style>
