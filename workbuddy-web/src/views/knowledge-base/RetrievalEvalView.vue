<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">检索评测</h1>
        <p class="page-desc">
          {{ kbName }} · 与生产检索同一执行引擎，测试台显式指定索引版本与检索规则
        </p>
      </div>
      <div class="page-actions">
        <span class="rt-lock">重排 / 预处理 / 后处理 暂未开放</span>
      </div>
    </div>

    <div class="page-panel rt-panel">
      <!-- 检索控件：一行放完（版本 / 规则 / query / 执行） -->
      <div class="rt-bar">
        <span class="rt-ctl rt-ctl-ver">
          <span class="rt-ctl-k">索引版本</span>
          <el-select v-model="versionId" size="small" placeholder="选择版本" class="rt-select">
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="`${v.versionNo}（${v.status}）`"
              :value="v.id"
            />
          </el-select>
        </span>
        <span class="rt-ctl rt-ctl-rule">
          <span class="rt-ctl-k">检索规则</span>
          <el-select v-model="ruleId" size="small" placeholder="选择规则" class="rt-select">
            <el-option
              v-for="r in rules"
              :key="r.id"
              :label="strategyDisplayName(r)"
              :value="r.id"
            />
          </el-select>
        </span>
        <el-input
          v-model="queryText"
          class="rt-query"
          size="small"
          placeholder="输入查询，回车执行"
          clearable
          @keyup.enter="runSearch"
        />
        <el-button
          class="rt-run-btn"
          type="primary"
          size="small"
          :loading="searching"
          :disabled="!canRun"
          @click="runSearch"
        >
          执行检索
        </el-button>
      </div>

      <!-- 左右分栏：左=结果，右=记录 / 对比（分段切换） -->
      <div class="rt-split">
        <!-- ==================== 左：检索结果 ==================== -->
        <section class="rt-left">
          <div v-if="searching" class="rt-hint">检索中…</div>
          <div v-else-if="!current" class="rt-hint">
            <span>选择索引版本与检索规则，输入查询后执行</span>
            <span class="rt-hint-sub"
              >测试台必须显式指定两者 —— 这样才能对比"同一 query 下不同规则的表现"</span
            >
          </div>
          <div v-else-if="current.hits.length === 0" class="rt-hint">
            <span>没有命中</span>
            <span class="rt-hint-sub">{{ current.query }}</span>
          </div>
          <div v-else class="rt-hits">
            <article v-for="(hit, i) in current.hits" :key="`${hit.chunkId}-${i}`" class="rt-hit">
              <span class="rt-rank">{{ i + 1 }}</span>
              <div class="rt-hit-body">
                <div class="rt-hit-line">
                  <span class="rt-hit-path">{{ hit.titlePath || '（无标题路径）' }}</span>
                  <span v-if="hit.isParent" class="rt-tag rt-tag-parent">父片展开</span>
                  <span v-if="hit.contentType" class="rt-tag rt-tag-mute">{{
                    hit.contentType
                  }}</span>
                  <span class="rt-hit-cid">{{ hit.chunkId }}</span>
                </div>
                <p class="rt-hit-text">{{ hit.content }}</p>
              </div>
              <div class="rt-hit-score">
                {{ hit.score === null ? '—' : hit.score.toFixed(4) }}
                <span class="rt-hit-score-k">{{ hit.score === null ? '纯全文' : 'RRF' }}</span>
              </div>
            </article>
          </div>
          <div v-if="current" class="page-panel-foot">
            <span>命中 {{ current.hits.length }} 条 · {{ current.elapsedMs ?? '—' }} ms</span>
            <span class="rt-rec">{{
              current.runId ? '本次运行已记录' : '未记录（生产检索按开关）'
            }}</span>
          </div>
        </section>

        <!-- ==================== 右：记录 ⇄ 对比 ==================== -->
        <section class="rt-right">
          <div class="rt-seg">
            <button
              v-for="tab in RIGHT_TABS"
              :key="tab.key"
              class="rt-seg-btn"
              :class="{ 'is-on': rightTab === tab.key }"
              type="button"
              @click="rightTab = tab.key"
            >
              {{ tab.label
              }}<span v-if="tab.key === 'compare' && pickedRuns.length" class="rt-seg-n">{{
                pickedRuns.length
              }}</span>
            </button>
            <span class="page-spacer"></span>
            <el-button
              v-if="rightTab === 'compare' && compareResult.length"
              class="rt-publish"
              type="primary"
              size="small"
              :loading="publishing"
              @click="publishWinning"
            >
              把胜出规则设为默认
            </el-button>
          </div>

          <!-- 记录列表 -->
          <div v-if="rightTab === 'runs'" class="rt-runs">
            <div v-if="runs.length === 0" class="rt-hint">
              <span>还没有运行记录</span>
              <span class="rt-hint-sub">每次测试台检索都会自动记录，供后续并排对比</span>
            </div>
            <label v-for="run in runs" :key="run.id" class="rt-run">
              <input
                class="rt-run-check"
                type="checkbox"
                :checked="pickedRuns.includes(run.id)"
                @change="toggleRun(run.id)"
              />
              <span class="rt-run-main">
                <span class="rt-run-rule">{{ run.ruleNameVersion }}</span>
                <span class="rt-run-sub"
                  >{{ run.query }} · {{ run.versionNo }} ·
                  {{ run.createTime ? formatTime(run.createTime) : '—' }}</span
                >
              </span>
              <span class="rt-run-ms">{{ run.elapsedMs }} ms</span>
            </label>
          </div>

          <!-- 并排对比 -->
          <div v-else class="rt-compare">
            <div v-if="compareLoading" class="rt-hint">加载对比…</div>
            <div v-else-if="compareResult.length === 0" class="rt-hint">
              <span>还没有选记录</span>
              <span class="rt-hint-sub"
                >回到「运行记录」勾选 2~4 条，即可并排回放它们的执行时刻快照</span
              >
            </div>
            <template v-else>
              <div class="rt-cols" :style="columnStyle">
                <div
                  v-for="col in compareResult"
                  :key="col.runId ?? col.ruleNameVersion"
                  class="rt-col"
                >
                  <div class="rt-col-hd">
                    <span class="rt-col-rule">{{ col.ruleNameVersion }}</span>
                    <span class="rt-col-meta">{{ col.elapsedMs }}ms · {{ col.hits.length }}</span>
                  </div>
                  <div
                    v-for="(hit, i) in col.hits"
                    :key="`${hit.chunkId}-${i}`"
                    class="rt-mini"
                    :class="sharedClass(hit.chunkId)"
                  >
                    <span class="rt-mini-rank">{{ i + 1 }}</span>
                    <span class="rt-mini-text" :title="hit.content">{{
                      hit.titlePath || hit.content
                    }}</span>
                    <span class="rt-mini-score">{{
                      hit.score === null ? '—' : hit.score.toFixed(4)
                    }}</span>
                  </div>
                  <div v-for="pad in padCount(col)" :key="`pad-${pad}`" class="rt-mini is-pad">
                    <span class="rt-mini-rank">—</span>
                    <span class="rt-mini-text">（无第 {{ col.hits.length + pad }} 条命中）</span>
                    <span class="rt-mini-score">—</span>
                  </div>
                </div>
              </div>
              <div class="rt-legend">
                <span><i class="rt-dot rt-dot-shared"></i>两条都有 {{ sharedCount }}</span>
                <span><i class="rt-dot rt-dot-only"></i>仅此条有 {{ onlyCount }}</span>
                <span class="page-spacer"></span>
                <span v-if="overlapText">命中重合度 {{ overlapText }}</span>
              </div>
            </template>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 检索评测页。
 *
 * <p>**与生产检索共用同一执行引擎**：生产走回退链（在线版本行 → kb 默认 → 引擎基线），
 * 测试台只多一层显式的 (versionId, ruleId) —— 这样才能做"同一 query 下不同规则"的对比。
 *
 * <p>**评测 = 横向对比，不是打分**：`wb_knowledge_retrieval_run` 只存「四元组 + 执行时刻快照」，
 * **没有 ground truth**，算不出 recall / precision。页面把多次运行的结果**并排**呈现 +
 * 标出命中差异（共有 / 独有 / 重合度），由人判断哪条规则更好，
 * 再把胜出的规则发布为该索引版本的默认规则。
 *
 * <p>**对比回放不重跑**：索引集合是 append-only 的，重跑得到的候选集与记录不一致，
 * 对比只回放记录里的 `result_snapshot`（规格 D17-5「快照即证据」）。
 */
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';

import {
  addRetrievalRunsCompare,
  getRetrievalRuns,
  updateRetrievalRulePublish,
  addRetrievalTest,
} from '@/api/retrieval';
import { getIndexVersions } from '@/api/index-build';
import { getKnowledgeBaseDetail } from '@/api/knowledge-base';
import { getStrategyVersions } from '@/api/strategy';
import { strategyDisplayName } from '@/types/strategy';
import type { StrategyVersion } from '@/types/strategy';
import type { IndexVersionVO } from '@/types/index-record';
import type { RetrievalRun, SearchResult } from '@/types/retrieval';
import { formatTime } from '@/utils/date';

/** 右栏的两个模式 */
const RIGHT_TABS = [
  { key: 'runs' as const, label: '运行记录' },
  { key: 'compare' as const, label: '并排对比' },
];

/** 一次最多并排几条：再多每列内容会被挤掉 */
const MAX_COMPARE = 4;

const route = useRoute();
const knowledgeBaseId = computed(() => String(route.params.id));

const kbName = ref('');
const versions = ref<IndexVersionVO[]>([]);
const rules = ref<StrategyVersion[]>([]);

const versionId = ref('');
const ruleId = ref('');
const queryText = ref('');

const searching = ref(false);
const current = ref<SearchResult | null>(null);

const runs = ref<RetrievalRun[]>([]);
const pickedRuns = ref<string[]>([]);
const rightTab = ref<'runs' | 'compare'>('runs');
const compareResult = ref<SearchResult[]>([]);
const compareLoading = ref(false);
const publishing = ref(false);

const canRun = computed(() => Boolean(versionId.value && ruleId.value && queryText.value.trim()));

/** 对比列宽：2 列均分，3~4 列时自动变窄（CSS grid 处理，这里只给列数） */
const columnStyle = computed(() => ({
  gridTemplateColumns: `repeat(${Math.max(1, compareResult.value.length)}, minmax(0, 1fr))`,
}));

/** 所有对比列里出现过的 chunkId → 出现次数（用于标"共有/独有"） */
const chunkFrequency = computed(() => {
  const freq = new Map<string, number>();
  for (const col of compareResult.value) {
    for (const hit of col.hits) {
      freq.set(hit.chunkId, (freq.get(hit.chunkId) ?? 0) + 1);
    }
  }
  return freq;
});

const sharedCount = computed(() => [...chunkFrequency.value.values()].filter((n) => n >= 2).length);

const onlyCount = computed(() => [...chunkFrequency.value.values()].filter((n) => n === 1).length);

/** 重合度：各列共有的片数 / 出现过的不重复片数 */
const overlapText = computed(() => {
  const total = chunkFrequency.value.size;
  if (total === 0) {
    return '';
  }
  return `${sharedCount.value} / ${total} = ${Math.round((sharedCount.value / total) * 100)}%`;
});

/**
 * 该命中在参与对比的各列里是否共有 → 返回要挂的类名。
 *
 * <p>`rt-hit-shared` / `rt-hit-only` 这两个类定义在全局样式里（不是本组件的 scoped），
 * 原因：类名由本函数动态算出，而 `check-spec` 只做静态扫描、看不见计算值里的类名，
 * 会误报"样式类在模板中未使用"。项目里 `chain-graph.css` 是同样的先例
 * （Vue Flow 生成的动态 DOM 也放全局）。类名带 `rt-` 前缀避免与其它页面撞车。
 */
function sharedClass(chunkId: string): string {
  const n = chunkFrequency.value.get(chunkId) ?? 0;
  if (compareResult.value.length < 2) {
    return '';
  }
  return n >= 2 ? 'rt-hit-shared' : 'rt-hit-only';
}

/** 补齐行数，让各列"第 N 条"横向对齐 */
function padCount(col: SearchResult): number[] {
  const max = Math.max(...compareResult.value.map((c) => c.hits.length));
  const missing = max - col.hits.length;
  return missing > 0 ? Array.from({ length: missing }, (_, i) => i + 1) : [];
}

function toggleRun(id: string): void {
  const picked = pickedRuns.value;
  const at = picked.indexOf(id);
  if (at >= 0) {
    picked.splice(at, 1);
  } else {
    if (picked.length >= MAX_COMPARE) {
      ElMessage.warning(`最多并排 ${MAX_COMPARE} 条`);
      return;
    }
    picked.push(id);
  }
  void loadCompare();
}

async function loadVersions(): Promise<void> {
  try {
    versions.value = await getIndexVersions(knowledgeBaseId.value);
    // 默认选最新一条（接口新→旧）
    versionId.value = versions.value[0]?.id ?? '';
  } catch {
    versions.value = [];
  }
}

async function loadRules(): Promise<void> {
  try {
    const list = await getStrategyVersions('RETRIEVAL', false);
    rules.value = list;
    ruleId.value = list[0]?.id ?? '';
  } catch {
    rules.value = [];
  }
}

async function loadRuns(): Promise<void> {
  try {
    runs.value = await getRetrievalRuns(knowledgeBaseId.value);
  } catch {
    runs.value = [];
  }
}

async function loadCompare(): Promise<void> {
  if (pickedRuns.value.length === 0) {
    compareResult.value = [];
    return;
  }
  compareLoading.value = true;
  try {
    compareResult.value = await addRetrievalRunsCompare(knowledgeBaseId.value, pickedRuns.value);
  } catch {
    compareResult.value = [];
  } finally {
    compareLoading.value = false;
  }
}

async function runSearch(): Promise<void> {
  if (!canRun.value) {
    return;
  }
  searching.value = true;
  try {
    current.value = await addRetrievalTest(knowledgeBaseId.value, {
      query: queryText.value.trim(),
      versionId: versionId.value,
      ruleId: ruleId.value,
    });
    // 执行即落记录，刷新列表让新记录可被勾选
    await loadRuns();
  } catch {
    current.value = null;
  } finally {
    searching.value = false;
  }
}

/**
 * 把**对比里选中的第一条规则**发布为该索引版本的默认规则。
 *
 * <p>不做"自动挑胜出"：没有 ground truth，谁胜出只有人能判断。
 * 这里只是把"当前正在对比的第一条"作为候选，并在确认框里把规则名写清楚。
 */
async function publishWinning(): Promise<void> {
  const winnerRuleId = pickedRunRuleId();
  if (!winnerRuleId) {
    ElMessage.warning('找不到要发布的规则（对应的运行记录缺少规则 ID）');
    return;
  }
  const ruleName = rules.value.find((r) => r.id === winnerRuleId);
  try {
    await ElMessageBox.confirm(
      `把「${ruleName ? strategyDisplayName(ruleName) : winnerRuleId}」设为索引版本 ${
        versions.value.find((v) => v.id === versionId.value)?.versionNo ?? versionId.value
      } 的默认规则？生产检索未显式指定规则时将使用它。`,
      '选优发布',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  publishing.value = true;
  try {
    await updateRetrievalRulePublish(knowledgeBaseId.value, versionId.value, winnerRuleId);
    ElMessage.success('已发布为默认规则');
  } catch {
    // 失败提示由接口层统一处理
  } finally {
    publishing.value = false;
  }
}

/** 对比第一条对应的运行记录 → 它的规则 ID */
function pickedRunRuleId(): string | null {
  const firstPicked = pickedRuns.value[0];
  const run = runs.value.find((r) => r.id === firstPicked);
  return run?.ruleId ?? null;
}

onMounted(async () => {
  try {
    const kb = await getKnowledgeBaseDetail(knowledgeBaseId.value);
    kbName.value = kb.name;
  } catch {
    kbName.value = '';
  }
  await Promise.all([loadVersions(), loadRules(), loadRuns()]);
});
</script>

<style scoped lang="css">
/*
 * 布局要点：
 * - 检索控件压成**一行**（版本 / 规则 / query / 执行），不折行；
 * - 左右分栏：左=结果（1.45fr），右=记录 ⇄ 对比（分段切换）；
 * - 右栏的对比**占满整栏**。
 */
.rt-panel {
  min-height: 520px;
}

.rt-lock {
  padding: 2px 9px;
  border: 1px dashed var(--kb-line);
  border-radius: 999px;
  color: var(--kb-text-3);
  font-size: 11px;
}

/* ==================== 检索控件一行 ==================== */
.rt-bar {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  padding: 11px 14px;
  border-bottom: 1px solid var(--kb-line);
}

.rt-ctl {
  display: inline-flex;
  gap: 7px;
  align-items: center;
  flex: none;
}

.rt-ctl-k {
  color: var(--kb-text-3);
  font-size: 11px;
  white-space: nowrap;
}

.rt-select {
  width: 100%;
}

.rt-ctl-ver {
  width: 150px;
}

.rt-ctl-rule {
  flex: 1;
  min-width: 180px;
}

.rt-query {
  flex: 1;
  min-width: 160px;
}

/* 「执行检索」按钮：名字避开 .rt-run（那是运行记录行） */
.rt-run-btn {
  flex: none;
}

/* ==================== 左右分栏 ==================== */
.rt-split {
  display: grid;
  flex: 1;
  min-height: 0;
  grid-template-columns: minmax(0, 1.45fr) minmax(320px, 1fr);
}

.rt-left {
  display: flex;
  min-width: 0;
  flex-direction: column;
  border-right: 1px solid var(--kb-line);
}

.rt-right {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

/* ==================== 空态 / 提示 ==================== */
.rt-hint {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  justify-content: center;
  padding: 26px 18px;
  color: var(--kb-text-3);
  font-size: 13px;
  text-align: center;
}

.rt-hint-sub {
  color: var(--kb-text-4);
  font-size: 11px;
  line-height: 1.6;
}

/* ==================== 命中列表 ==================== */
.rt-hits {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.rt-hit {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr) 62px;
  gap: 10px;
  align-items: start;
  padding: 10px 14px;
  border-bottom: 1px solid rgb(255 255 255 / 5%);
}

.rt-rank {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--kb-tint);
  color: var(--kb-primary);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  font-weight: 600;
}

.rt-hit-body {
  min-width: 0;
}

.rt-hit-line {
  display: flex;
  gap: 7px;
  align-items: center;
  margin-bottom: 3px;
  min-width: 0;
}

.rt-hit-path {
  overflow: hidden;
  color: var(--kb-text-2);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-hit-cid {
  flex: none;
  color: var(--kb-text-4);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 10px;
}

.rt-hit-text {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: var(--kb-text-3);
  font-size: 12px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.rt-hit-score {
  color: var(--kb-primary-2);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
  text-align: right;
}

.rt-hit-score-k {
  display: block;
  color: var(--kb-text-4);
  font-size: 10px;
  font-weight: 400;
}

.rt-tag {
  display: inline-flex;
  flex: none;
  align-items: center;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10px;
  white-space: nowrap;
}

.rt-tag-parent {
  background: rgb(52 211 153 / 12%);
  color: var(--kb-primary);
}

.rt-tag-mute {
  background: rgb(255 255 255 / 6%);
  color: var(--kb-text-3);
}

/* ==================== 右栏分段控件 ==================== */
.rt-seg {
  display: flex;
  flex: none;
  gap: 2px;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid var(--kb-line);
}

.rt-seg-btn {
  padding: 5px 11px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--kb-text-3);

  /* 显式写完整字体栈而不是 `font-family: inherit`：项目规范要求字体声明以通用族结尾 */
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  cursor: pointer;
}

.rt-seg-btn.is-on {
  background: var(--kb-tint);
  color: var(--kb-primary);
  font-weight: 600;
}

.rt-seg-n {
  margin-left: 5px;
  padding: 0 5px;
  border-radius: 999px;
  background: rgb(52 211 153 / 20%);
  font-size: 10px;
}

.rt-publish {
  margin: 4px 4px 4px 0;
}

/* ==================== 运行记录 ==================== */
.rt-runs {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.rt-run {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) 62px;
  gap: 9px;
  align-items: center;
  padding: 8px 14px;
  border-bottom: 1px solid rgb(255 255 255 / 5%);
  cursor: pointer;
}

.rt-run:hover {
  background: rgb(255 255 255 / 2.5%);
}

.rt-run-check {
  width: 14px;
  height: 14px;
  border: 1px solid var(--kb-line-strong);
  border-radius: 4px;
  appearance: none;
  cursor: pointer;
}

.rt-run-check:checked {
  border-color: transparent;
  background: var(--kb-primary);
  box-shadow: 0 0 7px var(--kb-tint);
}

.rt-run-main {
  min-width: 0;
}

.rt-run-rule {
  display: block;
  overflow: hidden;
  color: var(--kb-text-2);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-run-sub {
  display: block;
  overflow: hidden;
  color: var(--kb-text-4);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-run-ms {
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  text-align: right;
}

/* ==================== 并排对比 ==================== */
.rt-compare {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
}

.rt-cols {
  display: grid;
  flex: 1;
  gap: 10px;
  min-height: 0;
  padding: 11px 12px;
  overflow-y: auto;
}

.rt-col {
  min-width: 0;
  align-self: start;
  border: 1px solid var(--kb-line);
  border-radius: 9px;
  background: rgb(0 0 0 / 20%);
  overflow: hidden;
}

.rt-col-hd {
  display: flex;
  gap: 7px;
  align-items: center;
  padding: 7px 11px;
  border-bottom: 1px solid var(--kb-line);
  background: rgb(255 255 255 / 2%);
}

.rt-col-rule {
  overflow: hidden;
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-col-meta {
  flex: none;
  margin-left: auto;
  color: var(--kb-text-4);
  font-size: 10px;
}

.rt-mini {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) 48px;
  gap: 7px;
  align-items: center;
  padding: 7px 11px;
  border-bottom: 1px solid rgb(255 255 255 / 4%);
  font-size: 11px;
}

.rt-mini-rank {
  color: var(--kb-text-4);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 10px;
}

.rt-mini-text {
  overflow: hidden;
  color: var(--kb-text-3);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-mini-score {
  color: var(--kb-text-2);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  text-align: right;
}

/*
 * 「共有 / 独有」的色底（.rt-hit-shared / .rt-hit-only）定义在全局 page-shell.css。
 * 原因：这两个类名由 sharedClass() 动态算出，而 check-spec 只做静态扫描，
 * scoped 里定义会被误判为"样式类在模板中未使用"（chain-graph.css 是同样的先例）。
 */

/* 补齐行：弱化到几乎看不见，只起"对齐"作用 */
.rt-mini.is-pad {
  color: var(--kb-text-4);
  opacity: 0.5;
}

.rt-legend {
  display: flex;
  flex: none;
  gap: 13px;
  align-items: center;
  padding: 8px 12px;
  border-top: 1px solid var(--kb-line);
  color: var(--kb-text-3);
  font-size: 11px;
}

.rt-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  margin-right: 5px;
  border-radius: 2px;
  vertical-align: -1px;
}

.rt-dot-shared {
  background: rgb(163 230 53 / 45%);
}

.rt-dot-only {
  background: rgb(251 191 36 / 45%);
}

.rt-rec {
  color: var(--kb-text-3);
  font-size: 11px;
}
</style>
