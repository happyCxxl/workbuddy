<template>
  <div class="ib-page">
    <!-- ==================== 顶部：当前线上 + 构建入口 ==================== -->
    <div class="ib-top">
      <div v-if="onlineVersion" class="ib-live">
        <span class="ib-tag is-online"><i class="ib-tag-dot"></i>当前线上</span>
        <span class="ib-live-no">{{ onlineVersion.versionNo }}</span>
        <span class="ib-live-scope">{{ scopeText(onlineVersion) }}</span>
        <span class="ib-spacer"></span>
        <span class="ib-live-stat"
          ><b class="ib-num">{{ numberText(onlineVersion.chunkCount) }}</b> 片</span
        >
        <span class="ib-live-stat"
          ><b class="ib-num">{{ numberText(onlineVersion.vectorCount) }}</b> 向量</span
        >
        <span v-if="onlineVersion.validatedAt" class="ib-live-stat">
          {{ formatIndexTime(onlineVersion.validatedAt) }} 追加
        </span>
        <i
          class="ib-help"
          data-tip="发布不等于冻结：新文件处理完会自动追加进这个集合，所以片数/向量数会继续增长，不是快照。"
          >?</i
        >
      </div>
      <div v-else class="ib-live is-empty">
        <span class="ib-tag is-retired"><i class="ib-tag-dot"></i>未发布</span>
        <span class="ib-live-scope"> 该知识库还没有在线索引，检索会报「未发布任何索引版本」 </span>
      </div>

      <div v-if="!panelOpen" class="ib-build">
        <div class="ib-build-txt">
          <b class="ib-strong">构建新版本</b>
          选择策略组合与纳入的文件，后台构建为候选版本；构建过程不影响当前线上。
        </div>
        <button class="ib-btn is-primary" type="button" @click="openBuildPanel">＋ 构建索引</button>
      </div>
    </div>

    <!-- ==================== 构建面板（内联，不是弹窗）==================== -->
    <div v-if="panelOpen" class="ib-panel">
      <div class="ib-panel-head">
        <span class="ib-panel-title">构建新版本</span>
        <span class="ib-panel-sub">
          选一条策略组合，再决定纳入它的哪些文件；构建不影响当前线上。
        </span>
        <span class="ib-spacer"></span>
        <button class="ib-btn" type="button" @click="closeBuildPanel">收起</button>
      </div>

      <div v-if="combosLoading" class="ib-panel-empty">正在枚举可构建的组合…</div>

      <div v-else-if="combos.length === 0" class="ib-panel-empty">
        没有可构建的组合。一条组合需要「预处理 × 切片 × 向量化」三个环节的策略都成立，
        且至少有一个文件在这三个环节都有成功产物。
      </div>

      <template v-else>
        <div v-for="combo in combos" :key="combo.key" class="ib-cb">
          <div class="ib-cb-head" @click="toggleCombo(combo.key)">
            <span class="ib-caret" :class="{ 'is-open': isComboOpen(combo.key) }"></span>
            <span class="ib-cb-pipe">
              <span v-for="part in combo.parts" :key="part.stage" class="ib-cp">
                <span class="ib-cp-k">{{ part.label }}</span>
                <span class="ib-cp-v">{{ part.value }}</span>
              </span>
            </span>
            <span class="ib-spacer"></span>
            <span class="ib-cb-preview">
              {{ selectedCountOf(combo) }} / {{ combo.members.length }} 个文件 ·
              {{ numberText(combo.vectorCount) }} 向量
            </span>
            <span v-if="selectedCountOf(combo) === 0" class="ib-cb-warn">未选文件</span>
          </div>

          <div v-if="isComboOpen(combo.key)" class="ib-cb-files">
            <div class="ib-cb-files-head">
              <span class="ib-cb-files-label">纳入的文件（取消勾选即只构建子集）</span>
              <button class="ib-btn is-xs" type="button" @click="selectAllOf(combo)">全选</button>
            </div>
            <label
              v-for="file in combo.members"
              :key="file.id"
              class="ib-file"
              :class="{ 'is-on': isChosen(combo.key, file.id) }"
            >
              <input
                type="checkbox"
                :checked="isChosen(combo.key, file.id)"
                @change="toggleChosen(combo.key, file.id)"
              />
              <span class="ib-file-name">{{ file.fileName }}</span>
            </label>
          </div>
        </div>

        <!-- 没有完整产物的文件：说明存在与原因，而不是藏起来 -->
        <div v-if="unbuildable.length > 0" class="ib-panel-note">
          另有 {{ unbuildable.length }} 个文件尚未跑完三个环节，暂不可构建：
          <span v-for="(name, index) in unbuildableNames" :key="name">
            <span v-if="index > 0">、</span>{{ name }}
          </span>
          <button class="ib-link" type="button" @click="goStages">去处理链补齐</button>
        </div>
      </template>

      <div class="ib-panel-foot">
        <button class="ib-btn" type="button" @click="closeBuildPanel">取消</button>
        <button
          class="ib-btn is-primary"
          type="button"
          :disabled="!canSubmit"
          @click="openBuildConfirm"
        >
          构建
        </button>
      </div>
    </div>

    <!-- ==================== 主体：组合卡（一条组合一条发布线） ==================== -->
    <div class="ib-body">
      <div v-if="loading" class="ib-empty">加载中…</div>

      <template v-else-if="groups.length === 0">
        <div class="ib-empty-box">
          <div class="ib-empty-title">还没有索引版本</div>
          <div class="ib-empty-desc">
            索引版本由「预处理 × 切片 × 向量化」三个环节的策略组合构成，
            且需要文件在这三个环节都有成功产物。
          </div>
          <div class="ib-empty-actions">
            <button class="ib-btn is-primary" type="button" @click="openBuildPanel">
              ＋ 构建索引
            </button>
            <button class="ib-btn" type="button" @click="goStages">去看处理链</button>
          </div>
        </div>
      </template>

      <template v-else>
        <div v-for="group in groups" :key="group.key" class="ib-group">
          <div class="ib-group-head">
            <div class="ib-pipe">
              <template v-for="(part, index) in group.parts" :key="part.stage">
                <span v-if="index > 0" class="ib-pipe-arrow">›</span>
                <span class="ib-cp">
                  <span class="ib-cp-k">{{ part.label }}</span>
                  <span class="ib-cp-v">{{ part.value }}</span>
                </span>
              </template>
            </div>
            <span class="ib-spacer"></span>
            <span v-if="group.failedCount > 0" class="ib-gap" :data-tip="group.gapTip">
              {{ group.failedCount }} 个失败
            </span>
            <span class="ib-group-agg"
              ><b class="ib-num">{{ group.versions.length }}</b> 个版本</span
            >
            <button
              v-if="group.validatableId"
              class="ib-btn is-sm"
              type="button"
              @click="runValidate(group.validatableId)"
            >
              校验
            </button>
          </div>

          <div class="ib-line">
            <template v-for="(version, index) in group.versions" :key="version.id">
              <span v-if="index > 0" class="ib-connector"></span>
              <div
                class="ib-node"
                :class="{
                  'is-live': version.online,
                  'is-building': isBuilding(version),
                  'is-failed': version.status === 'FAILED',
                }"
              >
                <span class="ib-node-no">{{ version.versionNo }}</span>
                <span class="ib-node-scope">{{ scopeText(version) }}</span>
                <span class="ib-tag" :class="`is-${indexStatusTone(version.status)}`">
                  <i class="ib-tag-dot"></i>{{ indexStatusLabel(version.status) }}
                </span>

                <div v-if="isBuilding(version)" class="ib-prog">
                  <div class="ib-prog-cap">{{ buildStageText(version) }}</div>
                  <div class="ib-bar"><i class="ib-bar-fill"></i></div>
                </div>

                <div class="ib-node-ops">
                  <button
                    v-if="canValidate(version)"
                    class="ib-btn is-xs"
                    type="button"
                    @click="runValidate(version.id)"
                  >
                    校验
                  </button>
                  <!-- 发布/回退一律展示：不可用时置灰，保留"有这个能力但此刻不行"的可见性。
                       说明用 title 而不是 data-tip —— 禁用的按钮不派发鼠标事件，
                       CSS :hover 气泡永远不会出现，只有浏览器原生 title 仍会显示 -->
                  <button
                    class="ib-btn is-xs"
                    type="button"
                    :disabled="!canPublish(version)"
                    :title="publishBlockReason(version)"
                    @click="confirmPublish(version)"
                  >
                    发布
                  </button>
                  <button
                    class="ib-btn is-xs"
                    type="button"
                    :disabled="!canRollback(version)"
                    :title="rollbackBlockReason(version)"
                    @click="confirmRollback(version)"
                  >
                    回退
                  </button>
                  <button
                    v-if="canRetry(version)"
                    class="ib-btn is-xs"
                    type="button"
                    data-tip="补齐缺失产物后按原组合快照重跑；同内容重跑是幂等的"
                    @click="retryBuild(version)"
                  >
                    重试
                  </button>
                  <button
                    v-if="canDelete(version)"
                    class="ib-btn is-xs"
                    type="button"
                    @click="confirmDelete(version)"
                  >
                    删除
                  </button>
                </div>
              </div>
            </template>
          </div>
        </div>

        <div class="ib-legend">
          <span class="ib-lg"><i class="ib-dot is-online"></i>在线（当前生效）</span>
          <span class="ib-lg"><i class="ib-dot is-ready"></i>就绪待发布</span>
          <span class="ib-lg"><i class="ib-dot is-building"></i>构建中</span>
          <span class="ib-lg"><i class="ib-dot is-failed"></i>失败</span>
          <span class="ib-lg"><i class="ib-dot is-retired"></i>已退役（保留可回退）</span>
        </div>
      </template>
    </div>

    <!-- ==================== 校验结果 ==================== -->
    <el-dialog v-model="validateVisible" title="索引校验结果" width="520px">
      <div v-if="validateResult" class="ib-validate">
        <div class="ib-validate-sum" :class="validateResult.passed ? 'is-ok' : 'is-bad'">
          {{ validateResult.passed ? '全部通过，可以发布' : '未全部通过，不建议发布' }}
        </div>
        <div v-for="item in validateResult.items" :key="item.name" class="ib-validate-item">
          <i class="ib-validate-dot" :class="item.passed ? 'is-ok' : 'is-bad'"></i>
          <span class="ib-validate-name">{{ validateItemLabel(item.name) }}</span>
          <span class="ib-validate-detail">{{ item.detail }}</span>
        </div>
      </div>
      <template #footer>
        <button class="ib-btn" type="button" @click="validateVisible = false">关闭</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 索引与发布页（设计样例 C：以「策略组合」为第一层组织）。
 *
 * <p>页面回答三个问题：
 *
 * <ol>
 *   <li>**现在线上是哪个** —— 顶部摘要 + 对应组合卡高亮；</li>
 *   <li>**我下一步能做什么** —— 发布/回退一律展示，不可用时置灰（显性感知能力存在）；</li>
 *   <li>**做了会怎样** —— 发布/回退的确认里写清影响面与补齐代价。</li>
 * </ol>
 *
 * <p>构建面板的两条口径（都是后端定的）：
 *
 * <ul>
 *   <li>**以组合为第一层**：`GET /index-combos` 返回「组合 × 成员文件」，
 *       成员 = 该组合下有完整成功产物链的文件。先选组合，再决定纳入哪些成员；</li>
 *   <li>**范围由选择推导**：勾了全部产物齐全的文件 → ALL（全库，会接纳后续新文件）；
 *       少勾任何一个 → LIST（子集，只认这批 fileId）。两者都是索引，
 *       区别只在"新文件是否自动进来"，面板里不需要"范围"这个概念。</li>
 * </ul>
 *
 * <p>**能不能发布不在本页判定**：后端 `publish`/`rollback` 对子集拦截（40449），
 * 页面只按状态与范围决定按钮是否可用，不替发布环节做业务判断。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';

import {
  addIndexBuild,
  deleteIndexVersion,
  getIndexCombos,
  getIndexVersions,
  updateIndexVersionPublish,
  updateIndexVersionRollback,
  updateIndexVersionValidate,
} from '@/api/index-build';
import { getFileResults } from '@/api/pipeline';
import {
  comboKeyOf,
  comboParts,
  formatIndexTime,
  indexStatusLabel,
  indexStatusTone,
  isBuilding,
  isSubsetScope,
  scopeText,
  type IndexComboVO,
  type IndexVersionVO,
} from '@/types/index-record';
import {
  canDelete,
  canPublish,
  canRetry,
  canRollback,
  canValidate,
  validateItemLabel,
  type IndexValidateVO,
} from '@/types/index-publish';
import type { FileResult } from '@/types/pipeline';
/** 构建中每 4 秒刷新：这是分钟级任务，4 秒足够且不会让活账本数字跳得看不清 */
const POLL_INTERVAL_MS = 4000;

const route = useRoute();
const router = useRouter();
const knowledgeBaseId = computed(() => String(route.params.id));

const versions = ref<IndexVersionVO[]>([]);
const loading = ref(false);

// ============================================================
// 版本分组：一条组合 = 一条发布线
// ============================================================

interface VersionGroup {
  key: string;
  parts: { stage: string; label: string; value: string }[];
  versions: IndexVersionVO[];
  validatableId: string | null;
  failedCount: number;
  gapTip: string;
}

function pickValidatableId(list: IndexVersionVO[]): string | null {
  const preferred =
    list.find((item) => item.online && canValidate(item)) ??
    list.find((item) => item.status === 'READY' && canValidate(item)) ??
    list.find((item) => canValidate(item));
  return preferred?.id ?? null;
}

const groups = computed<VersionGroup[]>(() => {
  const byKey = new Map<string, IndexVersionVO[]>();
  for (const version of versions.value) {
    const key = comboKeyOf(version.stageStrategies);
    const list = byKey.get(key) ?? [];
    list.push(version);
    byKey.set(key, list);
  }
  const result: VersionGroup[] = [...byKey.entries()].map(([key, list]) => {
    const sorted = [...list].sort((a, b) =>
      b.versionNo.localeCompare(a.versionNo, undefined, { numeric: true }),
    );
    const failed = sorted.filter((item) => item.status === 'FAILED');
    return {
      key,
      parts: comboParts(sorted[0]?.stageStrategies ?? null),
      versions: sorted,
      validatableId: pickValidatableId(sorted),
      failedCount: failed.length,
      gapTip:
        failed.length > 0
          ? `${failed.map((item) => item.versionNo).join('、')} 构建失败：组合产物不完整。补齐缺失环节的产物后可点「重试」，不会改动组合快照。`
          : '',
    };
  });
  return result.sort((a, b) => {
    const aOnline = a.versions.some((item) => item.online) ? 1 : 0;
    const bOnline = b.versions.some((item) => item.online) ? 1 : 0;
    if (aOnline !== bOnline) {
      return bOnline - aOnline;
    }
    return (b.versions[0]?.createTime ?? '').localeCompare(a.versions[0]?.createTime ?? '');
  });
});

const onlineVersion = computed(() => versions.value.find((item) => item.online) ?? null);

function numberText(value: number | null | undefined): string {
  return typeof value === 'number' ? value.toLocaleString('en-US') : '0';
}

/** 构建中的文字：用「阶段 + 绝对计数」，不写百分比、不给预计时间 */
function buildStageText(version: IndexVersionVO): string {
  if (version.status === 'CREATED') {
    return '正在建集合…';
  }
  return version.chunkCount > 0 ? `已写入 ${numberText(version.chunkCount)} 片` : '正在对账…';
}

/**
 * 发布按钮不可用的原因（只解释点不动，不替发布环节做判断）。
 *
 * <p>禁用的按钮不派发鼠标事件，浏览器原生 title 仍会显示，这里用 title
 * 而不是应用内的 data-tip 气泡。
 */
function publishBlockReason(version: IndexVersionVO): string {
  if (version.online) {
    return '这个版本已经是在线版本';
  }
  if (isSubsetScope(version)) {
    return '子集索引用于测试策略效果，不能发布上线；正式上线请用全库构建的版本';
  }
  if (isBuilding(version)) {
    return '构建中，完成后才能发布';
  }
  if (version.status === 'FAILED') {
    return '构建失败，先补齐产物并重试';
  }
  if (version.status === 'RETIRED') {
    return '已退役的版本要用「回退」切回，而不是发布';
  }
  return '';
}

function rollbackBlockReason(version: IndexVersionVO): string {
  if (version.online) {
    return '这个版本已经是在线版本';
  }
  if (isSubsetScope(version)) {
    return '子集索引不能发布或回退';
  }
  if (isBuilding(version)) {
    return '构建中，无法回退';
  }
  if (version.status === 'FAILED') {
    return '构建失败的版本不能回退';
  }
  return '';
}

// ============================================================
// 数据加载与轮询
// ============================================================

async function loadVersions(silent = false): Promise<void> {
  if (!knowledgeBaseId.value) {
    return;
  }
  if (!silent) {
    loading.value = true;
  }
  try {
    versions.value = await getIndexVersions(knowledgeBaseId.value);
  } catch {
    // 失败提示已由 http 拦截器统一给出
  } finally {
    loading.value = false;
  }
}

const hasRunningTask = computed(() => versions.value.some(isBuilding));
let pollTimer: number | null = null;

function startPolling(): void {
  if (pollTimer !== null) {
    return;
  }
  pollTimer = window.setInterval(() => {
    if (hasRunningTask.value) {
      void loadVersions(true);
    }
  }, POLL_INTERVAL_MS);
}

onMounted(async () => {
  await loadVersions();
  startPolling();
});

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
});

watch(hasRunningTask, (running) => {
  if (running) {
    startPolling();
  }
});

watch(knowledgeBaseId, async () => {
  versions.value = [];
  combos.value = [];
  chosen.value = {};
  expandedCombos.value = new Set();
  await loadVersions();
});

// ============================================================
// 构建面板
// ============================================================

interface BuildCombo {
  key: string;
  parts: { stage: string; label: string; value: string }[];
  /** 该组合有完整产物的成员文件 */
  members: { id: string; fileName: string }[];
  stageStrategies: Record<string, string>;
  shape: string;
  vectorCount: number;
}

const panelOpen = ref(false);
const combosLoading = ref(false);
const combos = ref<BuildCombo[]>([]);
/** 每个组合里被勾选纳入的成员 fileId */
const chosen = ref<Record<string, string[]>>({});
const expandedCombos = ref<Set<string>>(new Set());
/** 已有产物、但尚未跑完三个环节的文件（列出来并说明，而不是藏起来） */
const unbuildable = ref<string[]>([]);

const unbuildableNames = computed(() => unbuildable.value.slice(0, 4));

function openBuildPanel(): void {
  panelOpen.value = true;
  void loadCombos();
}

function closeBuildPanel(): void {
  panelOpen.value = false;
}

function isComboOpen(key: string): boolean {
  return expandedCombos.value.has(key);
}

function toggleCombo(key: string): void {
  const next = new Set(expandedCombos.value);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  expandedCombos.value = next;
}

function isChosen(comboKey: string, fileId: string): boolean {
  return (chosen.value[comboKey] ?? []).includes(fileId);
}

function toggleChosen(comboKey: string, fileId: string): void {
  const current = chosen.value[comboKey] ?? [];
  const next = current.includes(fileId)
    ? current.filter((id) => id !== fileId)
    : [...current, fileId];
  chosen.value = { ...chosen.value, [comboKey]: next };
}

function selectAllOf(combo: BuildCombo): void {
  chosen.value = { ...chosen.value, [combo.key]: combo.members.map((m) => m.id) };
}

function selectedCountOf(combo: BuildCombo): number {
  return (chosen.value[combo.key] ?? []).length;
}

/** 某个环节是否成功（用于筛出可构建的文件） */
function stageSucceeded(file: FileResult, stage: string): boolean {
  return (file.stageStatuses ?? []).some(
    (item) => item.stage === stage && item.status === 'SUCCESS',
  );
}

/** 三个环节都有成功产物才算可构建 */
function artifactReady(file: FileResult): boolean {
  return (
    stageSucceeded(file, 'PREPROCESS') &&
    stageSucceeded(file, 'CHUNK') &&
    stageSucceeded(file, 'EMBED')
  );
}

async function loadCombos(): Promise<void> {
  combosLoading.value = true;
  try {
    // 文件清单与组合枚举一起拉：没跑完的文件单独说明，不进组合
    const [page, comboList] = await Promise.all([
      getFileResults(knowledgeBaseId.value, { current: 1, size: 200 }),
      getIndexCombos(knowledgeBaseId.value),
    ]);
    const nameById = new Map(page.records.map((file) => [file.id, file.fileName]));
    unbuildable.value = page.records.filter((file) => !artifactReady(file)).map((f) => f.fileName);

    const nextChosen: Record<string, string[]> = {};
    combos.value = comboList.map((item: IndexComboVO) => {
      const key = comboKeyOf(item.stageStrategies);
      const members = (item.fileResultIds ?? []).map((id) => ({
        id,
        fileName: nameById.get(id) ?? id,
      }));
      // 默认全选：多数情况下就是构建这条组合的全部，取消勾选才是子集
      nextChosen[key] = members.map((m) => m.id);
      return {
        key,
        parts: comboParts(item.stageStrategies),
        members,
        stageStrategies: item.stageStrategies ?? {},
        shape: item.shape ?? 'FULL_VECTOR',
        vectorCount: item.vectorCount,
      };
    });
    chosen.value = nextChosen;
    // 只有一个组合时直接展开
    expandedCombos.value = new Set(combos.value.length === 1 ? [combos.value[0].key] : []);
  } catch {
    combos.value = [];
    unbuildable.value = [];
  } finally {
    combosLoading.value = false;
  }
}

/** 当前可选择提交的组合：恰好一个组合勾了文件（多组合同时勾选会一次建多个版本，需要用户逐个来） */
const pendingCombos = computed(() => combos.value.filter((item) => selectedCountOf(item) > 0));

const canSubmit = computed(() => pendingCombos.value.length === 1);

/**
 * 提交前的确认：讲清会建出什么（全库还是子集）。
 *
 * <p>按钮在 `canSubmit` 为假时是禁用的，这里必然有一个待提交的组合；
 * 用 `.at(0)` 而不是 `[0]`，让"可能为空"这件事在类型上成立。
 */
async function openBuildConfirm(): Promise<void> {
  const combo = pendingCombos.value.at(0);
  if (!combo) {
    return;
  }
  const ids = chosen.value[combo.key] ?? [];
  const isAll = ids.length === combo.members.length;
  const scopeDesc = isAll
    ? `全库：纳入这条组合下全部 ${ids.length} 个文件，之后新跑完的文件会自动追加进来`
    : `子集：只纳入勾选的 ${ids.length} 个文件，之后新文件不会进来`;
  try {
    await ElMessageBox.confirm(
      `将按这条组合构建候选索引。\n${scopeDesc}\n构建在后台进行，不影响当前线上。`,
      '构建索引',
      { confirmButtonText: '开始构建', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  await submitBuild(combo, ids);
}

async function submitBuild(combo: BuildCombo, ids: string[]): Promise<void> {
  // 勾满成员 = ALL（会接纳后续新文件）；少勾 = LIST（只认这批 id）。
  // ALL 时 fileResultIds 送 null：后端对非 LIST 会强制归一化置空（buildCandidate 第 442 行），
  // 前端先送空，语义明确（非子集）
  const isAll = ids.length === combo.members.length;
  try {
    const result = await addIndexBuild(knowledgeBaseId.value, {
      fileScopeMode: isAll ? 'ALL' : 'LIST',
      fileResultIds: isAll ? null : ids,
      stageStrategies: combo.stageStrategies,
      shape: combo.shape,
      // 手动构建用 REBUILD：不会自动发布，构建完停在「就绪」等人工确认
      trigger: 'REBUILD',
    });
    panelOpen.value = false;
    ElMessage.success(`已提交构建，版本号 ${result.versionNo}`);
    await loadVersions();
  } catch {
    // 拦截器已提示（组合产物不完整会返回 40444）
  }
}

// ============================================================
// 校验 / 发布 / 回退 / 删除 / 重试
// ============================================================

const validateVisible = ref(false);
const validateResult = ref<IndexValidateVO | null>(null);

async function runValidate(versionId: string): Promise<void> {
  try {
    validateResult.value = await updateIndexVersionValidate(knowledgeBaseId.value, versionId);
    validateVisible.value = true;
  } catch {
    // 拦截器已提示（构建中返回 40443）
  }
}

/**
 * 发布确认：说清「谁会被顶掉」。
 *
 * <p>发布只是原子切指针、可被回退纠正，用普通二次确认 ——
 * 输入名称级的强确认只留给「删除」（唯一不可逆）。
 */
async function confirmPublish(version: IndexVersionVO): Promise<void> {
  const current = onlineVersion.value;
  const impact = current
    ? `发布后 ${version.versionNo} 成为在线版本，当前在线的 ${current.versionNo} 会自动退役（集合保留，可再回退）。`
    : `发布后 ${version.versionNo} 成为在线版本。`;
  try {
    await ElMessageBox.confirm(impact, '发布索引', {
      confirmButtonText: '发布',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  try {
    await updateIndexVersionPublish(knowledgeBaseId.value, version.id);
    ElMessage.success(`${version.versionNo} 已发布`);
    await loadVersions();
  } catch {
    // 拦截器已提示（子集会返回 40449）
  }
}

async function confirmRollback(version: IndexVersionVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `回退会把在线指针切回 ${version.versionNo}，当前在线版本自动退役。` +
        `期间新增的文件会按这个组合重新补齐，这是后台任务，可能需要一段时间。`,
      '回退索引',
      { confirmButtonText: '回退', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  try {
    await updateIndexVersionRollback(knowledgeBaseId.value, version.id);
    ElMessage.success(`已回退到 ${version.versionNo}`);
    await loadVersions();
  } catch {
    // 拦截器已提示
  }
}

/**
 * 删除：唯一不可逆的操作（物理 drop 集合），用输入版本号的最强确认。
 *
 * <p>校验走**返回值自己判断**，而不是 `ElMessageBox.prompt` 的 `inputValidator` 选项：
 * `inputValidator` 在 `ElMessageBoxOptions` 里是可选属性，静态检查会把它报成
 * "Unused property"。
 */
async function confirmDelete(version: IndexVersionVO): Promise<void> {
  let typed = '';
  try {
    const result = await ElMessageBox.prompt(
      `删除会立即物理释放该组合的向量集合，不可恢复。请输入版本号 ${version.versionNo} 确认。`,
      '删除索引版本',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        inputPlaceholder: version.versionNo,
      },
    );
    typed = typeof result.value === 'string' ? result.value : '';
  } catch {
    return;
  }
  // 输入不匹配即中止：不可逆操作不接受"点错也照删"
  if (typed !== version.versionNo) {
    ElMessage.warning(`输入的版本号不是 ${version.versionNo}，已取消删除`);
    return;
  }
  try {
    await deleteIndexVersion(knowledgeBaseId.value, version.id);
    ElMessage.success('已删除');
    await loadVersions();
  } catch {
    // 拦截器已提示（在线版本返回 40442）
  }
}

/** 重试：同组合重跑；后端同策略重跑幂等（确定性 chunkId + 复用向量账本） */
async function retryBuild(version: IndexVersionVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `按原组合快照重跑 ${version.versionNo}。同一内容重跑是幂等的，不会产生重复行。`,
      '重试构建',
      { confirmButtonText: '重试', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await addIndexBuild(knowledgeBaseId.value, {
      fileScopeMode: version.fileScopeMode,
      fileResultIds: version.fileResultIds,
      stageStrategies: version.stageStrategies ?? {},
      shape: version.shape ?? 'FULL_VECTOR',
      trigger: 'REBUILD',
    });
    ElMessage.success('已重新提交构建');
    await loadVersions();
  } catch {
    // 拦截器已提示（产物仍不完整返回 40444）
  }
}

function goStages(): void {
  void router.push(`/knowledge/${knowledgeBaseId.value}/stages`);
}
</script>

<style scoped lang="css">
.ib-page {
  display: flex;
  min-height: 100%;
  flex-direction: column;
}

.ib-spacer {
  flex: 1;
}

/* ==================== 顶部 ==================== */
.ib-top {
  padding: 16px 22px 0;
}

.ib-live {
  display: flex;
  gap: 11px;
  align-items: center;
  padding: 12px 15px;
  border: 1px solid rgb(52 211 153 / 26%);
  border-radius: 11px;
  background: rgb(52 211 153 / 6%);
}

.ib-live.is-empty {
  border-color: var(--kb-line-2);
  background: var(--kb-surface);
}

.ib-live-no {
  color: var(--kb-primary);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 16px;
  font-weight: 700;
}

.ib-live-scope {
  color: var(--kb-text-3);
  font-size: 12px;
}

.ib-live-stat {
  color: var(--kb-text-3);
  font-size: 12px;
}

.ib-live-stat .ib-num {
  color: var(--kb-text-1);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  font-weight: 600;
}

.ib-help {
  display: grid;
  width: 14px;
  height: 14px;
  border: 1px solid var(--kb-line-3);
  border-radius: 50%;
  color: var(--kb-text-3);
  font-size: 9px;
  font-style: normal;
  cursor: help;
  place-items: center;
}

.ib-help:hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.ib-build {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 14px 15px;
  margin-top: 12px;
  border: 1px solid var(--kb-line-2);
  border-radius: 11px;
  background: var(--kb-surface);
}

.ib-build-txt {
  flex: 1;
  color: var(--kb-text-3);
  font-size: 12px;
  line-height: 1.6;
}

.ib-build-txt .ib-strong {
  display: block;
  margin-bottom: 2px;
  color: var(--kb-text-1);
  font-size: 13px;
  font-weight: 650;
}

/* ==================== 构建面板（内联） ==================== */
.ib-panel {
  margin: 12px 22px 0;
  border: 1px solid rgb(52 211 153 / 30%);
  border-radius: 11px;
  background: rgb(52 211 153 / 4%);
}

.ib-panel-head {
  display: flex;
  gap: 11px;
  align-items: baseline;
  padding: 13px 16px;
  border-bottom: 1px solid rgb(52 211 153 / 16%);
}

.ib-panel-title {
  font-size: 13px;
  font-weight: 650;
}

.ib-panel-sub {
  color: var(--kb-text-3);
  font-size: 12px;
}

.ib-panel-empty {
  padding: 20px 16px;
  color: var(--kb-text-3);
  font-size: 12px;
  line-height: 1.8;
}

.ib-panel-note {
  padding: 11px 16px;
  border-top: 1px solid var(--kb-line);
  color: var(--kb-text-3);
  font-size: 12px;
  line-height: 1.7;
}

.ib-link {
  padding: 0;
  margin-left: 6px;
  border: none;
  background: transparent;
  color: var(--kb-primary);
  font-size: 12px;
  cursor: pointer;
  text-decoration: underline;
}

.ib-panel-foot {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--kb-line);
}

/* 组合行 */
.ib-cb {
  border-bottom: 1px solid var(--kb-line);
}

.ib-cb-head {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 11px 16px;
  cursor: pointer;
}

.ib-cb-head:hover {
  background: rgb(255 255 255 / 3%);
}

.ib-cb-pipe {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.ib-cb-preview {
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  white-space: nowrap;
}

.ib-cb-warn {
  padding: 1px 7px;
  border: 1px solid rgb(251 191 36 / 30%);
  border-radius: 5px;
  background: rgb(251 191 36 / 8%);
  color: var(--kb-warn);
  font-size: 11px;
}

.ib-caret {
  width: 0;
  height: 0;
  flex: none;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 5px solid var(--kb-text-3);
  transition: transform 0.15s;
}

.ib-caret.is-open {
  transform: rotate(90deg) translateX(1px);
}

.ib-cb-files {
  padding: 2px 16px 13px 34px;
}

.ib-cb-files-head {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
}

.ib-cb-files-label {
  color: var(--kb-text-3);
  font-size: 11px;
}

.ib-file {
  display: flex;
  gap: 9px;
  align-items: center;
  padding: 6px 9px;
  border: 1px solid transparent;
  border-radius: 7px;
  cursor: pointer;
}

.ib-file:hover {
  background: rgb(255 255 255 / 3%);
}

.ib-file.is-on {
  border-color: rgb(52 211 153 / 22%);
  background: rgb(52 211 153 / 6%);
}

.ib-file-name {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ==================== 按钮 ==================== */
.ib-btn {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--kb-line-2);
  border-radius: 8px;
  background: transparent;
  color: var(--kb-text-2);
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
  transition:
    border-color 0.13s,
    color 0.13s,
    background 0.13s;
}

.ib-btn:hover {
  border-color: var(--kb-line-3);
  color: var(--kb-text-1);
}

.ib-btn.is-primary {
  border-color: transparent;
  background: var(--kb-primary);
  color: var(--kb-btn-text);
  font-weight: 600;
}

.ib-btn.is-primary:hover {
  color: var(--kb-btn-text);
  filter: brightness(1.08);
}

.ib-btn.is-sm {
  height: 26px;
  padding: 0 10px;
  font-size: 12px;
}

.ib-btn.is-xs {
  height: 22px;
  padding: 0 7px;
  font-size: 11px;
}

.ib-btn[disabled] {
  opacity: 0.35;
  cursor: not-allowed;
}

/* ==================== 主体 ==================== */
.ib-body {
  flex: 1;
  padding: 18px 22px 34px;
}

.ib-empty {
  padding: 30px 0;
  color: var(--kb-text-3);
  font-size: 13px;
  text-align: center;
}

.ib-empty-box {
  padding: 30px 26px;
  border: 1px dashed var(--kb-line-2);
  border-radius: 11px;
  text-align: center;
}

.ib-empty-title {
  margin-bottom: 9px;
  font-size: 14px;
  font-weight: 650;
}

.ib-empty-desc {
  max-width: 520px;
  margin: 0 auto 17px;
  color: var(--kb-text-3);
  font-size: 12px;
  line-height: 1.8;
}

.ib-empty-actions {
  display: flex;
  gap: 9px;
  justify-content: center;
}

/* ==================== 组合卡 ==================== */
.ib-group {
  margin-bottom: 12px;
  border: 1px solid var(--kb-line);
  border-radius: 11px;
  overflow: hidden;
}

.ib-group-head {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 12px 15px;
  background: var(--kb-surface);
}

.ib-pipe {
  display: flex;
  gap: 7px;
  align-items: center;
  min-width: 0;
}

.ib-cp {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 5px 10px;
  border: 1px solid var(--kb-line-2);
  border-radius: 7px;
  background: var(--kb-bg-1);
}

.ib-cp-k {
  color: var(--kb-text-3);
  font-size: 10px;
  letter-spacing: 0.06em;
}

.ib-cp-v {
  color: var(--kb-text-1);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  white-space: nowrap;
}

.ib-pipe-arrow {
  flex: none;
  color: var(--kb-text-3);
}

.ib-group-agg {
  color: var(--kb-text-3);
  font-size: 11px;
}

.ib-group-agg .ib-num {
  color: var(--kb-text-2);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-weight: 600;
}

.ib-gap {
  padding: 1px 8px;
  border: 1px solid rgb(248 113 113 / 28%);
  border-radius: 5px;
  background: rgb(248 113 113 / 8%);
  color: var(--kb-danger);
  font-size: 11px;
  cursor: help;
}

/* ==================== 发布线 ==================== */
.ib-line {
  display: flex;
  gap: 0;
  align-items: stretch;
  padding: 13px 15px 15px;
}

.ib-node {
  display: flex;
  width: 132px;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  padding: 10px 7px;
  border: 1px solid var(--kb-line);
  border-radius: 9px;
  background: var(--kb-bg-1);
}

.ib-node.is-live {
  border-color: rgb(52 211 153 / 40%);
  background: rgb(52 211 153 / 8%);
}

.ib-node.is-building {
  border-color: rgb(251 191 36 / 35%);
  background: rgb(251 191 36 / 6%);
}

.ib-node.is-failed {
  border-color: rgb(248 113 113 / 35%);
  background: rgb(248 113 113 / 6%);
}

.ib-node-no {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  font-weight: 700;
}

.ib-node.is-live .ib-node-no {
  color: var(--kb-primary);
}

.ib-node-scope {
  color: var(--kb-text-3);
  font-size: 10px;
  white-space: nowrap;
}

.ib-connector {
  width: 20px;
  height: 1px;
  flex: none;
  align-self: center;
  background: var(--kb-line-2);
}

.ib-node-ops {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: center;
  margin-top: 1px;
}

.ib-prog {
  width: 100%;
  margin-top: 1px;
}

.ib-prog-cap {
  color: var(--kb-text-3);
  font-size: 10px;
  text-align: center;
}

.ib-bar {
  height: 3px;
  margin-top: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--kb-line-2);
}

.ib-bar .ib-bar-fill {
  display: block;
  width: 35%;
  height: 100%;
  border-radius: 999px;
  background: var(--kb-warn);
  animation: ib-slide 1.4s ease-in-out infinite;
}

@keyframes ib-slide {
  0% {
    transform: translateX(-100%);
  }

  100% {
    transform: translateX(340%);
  }
}

/* ==================== 状态徽标 ==================== */
.ib-tag {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 19px;
  padding: 0 7px;
  border: 1px solid transparent;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}

.ib-tag .ib-tag-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentcolor;
}

.ib-tag.is-online {
  border-color: rgb(52 211 153 / 38%);
  background: rgb(52 211 153 / 10%);
  color: var(--kb-primary);
}

.ib-tag.is-ready {
  border-color: rgb(96 165 250 / 35%);
  background: rgb(96 165 250 / 10%);
  color: var(--kb-info);
}

.ib-tag.is-building {
  border-color: rgb(251 191 36 / 35%);
  background: rgb(251 191 36 / 10%);
  color: var(--kb-warn);
}

.ib-tag.is-failed {
  border-color: rgb(248 113 113 / 35%);
  background: rgb(248 113 113 / 10%);
  color: var(--kb-danger);
}

.ib-tag.is-retired {
  border-color: var(--kb-line-2);
  color: var(--kb-text-3);
}

/* ==================== 图例 ==================== */
.ib-legend {
  display: flex;
  gap: 14px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 16px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.ib-lg {
  display: flex;
  gap: 5px;
  align-items: center;
}

.ib-dot {
  width: 7px;
  height: 7px;
  border-radius: 2px;
}

.ib-dot.is-online {
  background: var(--kb-primary);
}

.ib-dot.is-ready {
  background: var(--kb-info);
}

.ib-dot.is-building {
  background: var(--kb-warn);
}

.ib-dot.is-failed {
  background: var(--kb-danger);
}

.ib-dot.is-retired {
  background: var(--kb-line-3);
}

/* ==================== 校验结果 ==================== */
.ib-validate {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.ib-validate-sum {
  padding: 10px 12px;
  border-radius: 9px;
  font-size: 13px;
  font-weight: 600;
}

.ib-validate-sum.is-ok {
  border: 1px solid rgb(52 211 153 / 30%);
  background: rgb(52 211 153 / 8%);
  color: var(--kb-primary);
}

.ib-validate-sum.is-bad {
  border: 1px solid rgb(248 113 113 / 30%);
  background: rgb(248 113 113 / 8%);
  color: var(--kb-danger);
}

.ib-validate-item {
  display: flex;
  gap: 9px;
  align-items: center;
  padding: 9px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 8px;
}

.ib-validate-dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
}

.ib-validate-dot.is-ok {
  background: var(--kb-primary);
}

.ib-validate-dot.is-bad {
  background: var(--kb-danger);
}

.ib-validate-name {
  color: var(--kb-text-2);
  font-size: 12px;
  white-space: nowrap;
}

.ib-validate-detail {
  margin-left: auto;
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  text-align: right;
}
</style>
