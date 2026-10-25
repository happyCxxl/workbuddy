<template>
  <div class="stage-page">
    <!-- 无自带页头：内容直接占满 -->
    <div class="stage-body">
      <!-- 左：文件列表。可折叠 —— 收起后把宽度全让给右侧链图 -->
      <section class="stage-panel stage-files" :class="{ 'stage-files-collapsed': filesCollapsed }">
        <!--
          折叠开关：与左侧菜单栏同一款"激光缝"，同样骑在**右边界垂直中点**上。
          两处交互一致，不用为同一个动作学两种控件。
        -->
        <button
          class="stage-collapse"
          :class="{ 'is-collapsed': filesCollapsed }"
          type="button"
          :title="filesCollapsed ? '展开文件栏' : '收起文件栏'"
          aria-label="展开或收起文件栏"
          @click="toggleFilesCollapsed"
        ></button>

        <div class="stage-panel-head">
          <span class="stage-panel-title">文件</span>
        </div>

        <!--
          文件列表：**两种状态共用同一套 DOM**（收起时只把文字隐掉，不换结构）。

          <p>徽标元素全程存在，只有它的兄弟文字块收窄，过渡是连续的。
        -->
        <div
          v-if="files.length > 0"
          class="stage-file-list"
          :class="{
            'stage-file-list-icons': filesCollapsed,
            'stage-file-list-full': files.length === FILE_PAGE_SIZE,
          }"
        >
          <!--
            文件栏是**纯文件信息展示**：不反映处理进度与成败，那些在右侧链图看。
            这里只呈现"是哪个文件"：格式徽标 + 文件名 + 大小/提交时间。
            时间不可省 —— 同一文件重复导入时文件名与大小都一样，只有时间能区分。
          -->
          <div
            v-for="file in files"
            :key="file.id"
            class="stage-file"
            :data-file-id="file.id"
            :class="{ 'stage-file-on': file.id === selectedFileId }"
            :title="filesCollapsed ? file.fileName : undefined"
            @click="selectFile(file)"
          >
            <FileExtBadge
              class="stage-file-badge"
              :ext="fileExt(file.fileName)"
              :selected="file.id === selectedFileId"
            />
            <div class="stage-file-main">
              <span class="stage-file-name">{{ file.fileName }}</span>
              <!-- 存储徽标：标签给出这条结果落在哪种存储，配色与悬停提示给出能不能继续执行 -->
              <span
                v-if="file.storageType"
                class="stage-file-store"
                :data-tone="sameStorageSource(file) ? 'same' : 'other'"
                :title="fileStoreTitle(file)"
              >
                {{ fileStorageName(file.storageType) }}
              </span>
              <span class="stage-file-meta-text">
                {{ formatSize(file.fileSize) }}
                <template v-if="file.createTime"> · {{ formatTime(file.createTime) }}</template>
              </span>
            </div>
          </div>
        </div>
        <div v-else-if="filesLoading" class="stage-empty">加载中…</div>
        <div v-else class="stage-empty">该知识库还没有文件</div>
        <div class="stage-pager">
          <el-pagination
            v-model:current-page="fileQuery.current"
            layout="prev, pager, next"
            :total="total"
            :page-size="fileQuery.size"
            small
            @current-change="loadFiles"
          />
        </div>
      </section>

      <!-- 右：执行链（占满剩余宽度）。链图是一棵树：同一环节可以按不同策略反复分叉 -->
      <section class="stage-panel stage-chain-wrap">
        <div class="stage-canvas">
          <!--
            右侧是**一条 v-if 链**：同一时刻只有一个分支在场。
            分两条链写会让「加载中」与「触发解析」卡同时挂上去（切到没有运行的文件时，
            lineage 还是上一个文件的，卡片的判据看着是成立的）。

            分支口径：
            ① 等待超过提示延迟、且手上既没有图也没有当前文件的内容 → 占位；
            ② 没选文件 → 提示去左侧选；
            ③ 有图 → 链图（切文件时保留旧图并降透明度，不卸载重建）；
            ④ 当前文件确实没有运行 → 触发解析入口。
          -->
          <div v-if="showLoaderHint && !hasNodes && !lineageIsCurrent" class="stage-empty">
            加载中…
          </div>
          <div v-else-if="!selectedFileId" class="stage-empty">
            从左侧选择一个文件，查看它的处理链
          </div>
          <ChainGraph
            v-else-if="hasNodes"
            :lineage="lineage"
            :file-key="selectedFileId"
            :position-store="nodePositions"
            :bound-versions="boundVersions"
            :storage-block-reason="storageBlockReason"
            :class="{ 'is-refreshing': lineageLoading }"
            @select="onPathSelect"
            @trigger="openTrigger"
            @detail="openDetail"
          />

          <!-- 还没有任何运行：解析是链路起点、无上游产物，不需要「先选节点」，
               这里直接给入口 -->
          <div v-else-if="lineageIsCurrent" class="stage-start">
            <span class="stage-start-title">该文件还没有任何环节运行过</span>
            <span class="stage-start-hint">从解析开始——它不需要上游产物</span>
            <el-button
              class="stage-trigger-btn"
              type="primary"
              :loading="triggering"
              :disabled="storageBlockReason !== ''"
              @click="onTriggerParse"
            >
              解析
            </el-button>
            <!-- 不能继续执行的原因：按钮旁边显示，不覆盖按钮的加载态 -->
            <span v-if="storageBlockReason" class="stage-start-block">{{
              storageBlockReason
            }}</span>
          </div>
        </div>

        <div class="stage-legend">
          <span class="stage-legend-item"><i class="stage-nd tone-ok"></i>成功</span>
          <span class="stage-legend-item"><i class="stage-nd tone-partial"></i>部分成功</span>
          <span class="stage-legend-item"><i class="stage-nd tone-run"></i>运行中</span>
          <span class="stage-legend-item"><i class="stage-nd tone-fail"></i>失败</span>
          <span class="stage-legend-hit"
            ><i class="stage-nd tone-hit"></i>金色标记 = 命中知识库策略</span
          >
        </div>

        <!-- 触发弹窗挂右侧容器内：遮罩与居中都以链图区为参照，不落到整页视口 -->
        <TriggerStageDialog
          v-model="triggerVisible"
          :stage="triggerStage"
          :upstream="triggerSource"
          :submitting="triggering"
          :block-reason="storageBlockReason"
          :has-bound-strategy="triggerHasBoundStrategy"
          :bound-strategy-label="triggerBoundStrategyLabel"
          @confirm="onTriggerConfirm"
        />
      </section>
    </div>

    <!-- 环节详情抽屉：只看不改，打开与关闭都不影响链图的选中与轮询 -->
    <StageDetailDrawer
      v-if="detailNode"
      :visible="detailVisible"
      :file-result-id="selectedFileId"
      :task-id="detailNode.taskId"
      :stage="detailNode.stage"
      :file-name="selectedFileName"
      :source-file-id="selectedFileObjectId"
      @close="detailVisible = false"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRoute } from 'vue-router';

import { getKnowledgeBaseDetail } from '@/api/knowledge-base';
import { getFileResults, getLineage, getStrategyBinding, addStageTrigger } from '@/api/pipeline';
import ChainGraph from '@/components/pipeline/ChainGraph.vue';
import StageDetailDrawer from '@/components/pipeline/StageDetailDrawer.vue';
import TriggerStageDialog from '@/components/pipeline/TriggerStageDialog.vue';
import FileExtBadge from '@/components/knowledge-base/FileExtBadge.vue';
import { prunePositions, readPositions } from '@/utils/chain-layout-storage';
import { readCollapsed, writeCollapsed } from '@/utils/ui-state-storage';
import {
  PIPELINE_STAGES,
  STRATEGY_BINDING_TYPES,
  fileStorageName,
  isTaskPending,
  sameStorageSource,
  storageBlockReason as fileStorageBlockReason,
} from '@/types/pipeline';
import type {
  FileResult,
  Lineage,
  LineageNode,
  NodePosition,
  NodePositionStore,
  PipelineStage,
} from '@/types/pipeline';

// 环节页：左侧文件列表（纯文件信息，不反映处理进度）+ 右侧执行链。
// 链图是一棵树：同一环节可按不同策略反复分叉，分叉点由上游节点的 productId 指定
const route = useRoute();
const knowledgeBaseId = String(route.params.id);

const kbName = ref('');
const files = ref<FileResult[]>([]);
const total = ref(0);
const filesLoading = ref(false);
/** 文件栏每页条数：按栏内可用高度取 8，整页展示、翻页取剩余文件，不出现纵向滚动条 */
const FILE_PAGE_SIZE = 8;
const fileQuery = ref({ current: 1, size: FILE_PAGE_SIZE });

/** 文件栏折叠状态：收起后宽度全给右侧链图。偏好持久化，刷新后保持 */
const filesCollapsed = ref(readCollapsed('stage-files'));

function toggleFilesCollapsed(): void {
  filesCollapsed.value = !filesCollapsed.value;
  writeCollapsed('stage-files', filesCollapsed.value);
}

const selectedFileId = ref<string>('');
const lineage = ref<Lineage | null>(null);
const lineageLoading = ref(false);

/**
 * 血缘请求序号：只接受最后一次请求的响应。
 *
 * <p>连点两个文件时，先发的响应可能后到；直接赋值会把上一个文件的血缘画在当前选中项下，
 * 而坐标回填也是按当前选中项做的，两份数据都会错位。
 */
let lineageSeq = 0;

/** 加载提示的延迟：接口在窗口内返回就不显示「加载中…」，避免占位文本一闪而过 */
const LOADER_HINT_DELAY_MS = 150;
const showLoaderHint = ref(false);
let loaderHintTimer: number | null = null;

/** 开始计时：到点仍未有结果才把提示亮出来 */
function scheduleLoaderHint(): void {
  cancelLoaderHint();
  loaderHintTimer = window.setTimeout(() => {
    loaderHintTimer = null;
    showLoaderHint.value = true;
  }, LOADER_HINT_DELAY_MS);
}

/** 取消计时并收起提示 */
function cancelLoaderHint(): void {
  if (loaderHintTimer !== null) {
    window.clearTimeout(loaderHintTimer);
    loaderHintTimer = null;
  }
  showLoaderHint.value = false;
}

/** 选中路径的末端节点：从这里触发下游，其 productId 即分叉点 */
const pathEndNode = ref<LineageNode | null>(null);

/**
 * 本次触发的分叉点：点按钮那一刻记下的**被点那张卡**，弹窗展示与确认都用它。
 *
 * <p>与 {@link pathEndNode} 分开：选中值会随轮询刷新与用户改选变化，
 * 而一次触发的分叉点必须在点下的瞬间固定下来。
 */
const triggerSource = ref<LineageNode | null>(null);

/**
 * 各文件的节点坐标记忆：由页面持有，切文件再切回来能恢复用户摆好的布局。
 *
 * <p>放在页面而不是图组件里：切文件时 `selectedFileId` 立即变、`lineage` 要等接口返回，
 * 这个窗口期里图组件无法判断手上的坐标属于哪个文件；按文件 ID 存放就没有归属歧义。
 */
const nodePositions: NodePositionStore = new Map();
/** 执行树里是否已有运行节点：为空时要给「触发解析」入口 */
const hasNodes = computed(() => (lineage.value?.nodes ?? []).length > 0);

/**
 * 手上的 `lineage` 属于哪个文件。
 *
 * <p>切文件时 `lineage` 不清空（用来保住旧图），所以它可能还是**上一个文件**的数据；
 * 靠这个字段区分"当前文件的内容"与"上一个文件留下的画面"。
 */
const lineageFileId = ref('');

/** 手上的血缘是否就是当前选中文件的：切文件窗口期里为 false */
const lineageIsCurrent = computed(
  () => lineage.value !== null && lineageFileId.value === selectedFileId.value,
);

const triggerVisible = ref(false);
const triggering = ref(false);

/** 知识库当前绑定的策略版本（name-version 合成串集合），用于链图上的「命中策略」金标 */
const boundVersions = ref<Set<string>>(new Set());

/** 轮询句柄：触发后要等任务跑完，定时刷新执行树直到进入终态 */
let pollTimer: number | null = null;

/**
 * 某个节点之后的下一环节：已在链路末端的环节返回 null。
 *
 * <p>环节顺序是本页面的知识（`PIPELINE_STAGES`），链图不猜。
 */
function stageAfter(node: LineageNode | null): PipelineStage | null {
  const current = node?.stage;
  if (!current) {
    return null;
  }
  const index = PIPELINE_STAGES.indexOf(current as PipelineStage);
  if (index < 0 || index >= PIPELINE_STAGES.length - 1) {
    return null;
  }
  return PIPELINE_STAGES[index + 1];
}

/** 本次触发要跑的环节：**被点那张卡**的下一环节（与分叉点同源） */
const triggerStage = computed(() => stageAfter(triggerSource.value));

/** 各环节知识库绑定的策略展示名（键为策略类型；无绑定的环节没有该键） */
const boundStrategies = ref<Map<string, string>>(new Map());

async function loadStrategyBindings(): Promise<void> {
  const results = await Promise.all(
    STRATEGY_BINDING_TYPES.map((type) =>
      getStrategyBinding(knowledgeBaseId, type).catch(() => null),
    ),
  );
  const versions = new Set<string>();
  const bound = new Map<string, string>();
  results.forEach((binding, index) => {
    if (binding?.strategyName && binding.strategyVersion) {
      const label = `${binding.strategyName}-${binding.strategyVersion}`;
      versions.add(label);
      bound.set(STRATEGY_BINDING_TYPES[index], label);
    }
  });
  boundVersions.value = versions;
  boundStrategies.value = bound;
}

/** 本次触发环节已有知识库绑定（没有绑定即要求显式选策略） */
const triggerHasBoundStrategy = computed(() => boundStrategies.value.has(triggerStage.value ?? ''));

/** 本次触发环节绑定的策略展示名（没有绑定时为空串） */
const triggerBoundStrategyLabel = computed(
  () => boundStrategies.value.get(triggerStage.value ?? '') ?? '',
);

async function loadKnowledgeBase(): Promise<void> {
  try {
    const detail = await getKnowledgeBaseDetail(knowledgeBaseId);
    kbName.value = detail.name;
  } catch {
    // 失败提示已由接口层统一拦截处理
  }
}

async function loadFiles(): Promise<void> {
  filesLoading.value = true;
  try {
    const page = await getFileResults(knowledgeBaseId, fileQuery.value);
    files.value = page.records;
    total.value = page.total;
    // 首次加载自动选中第一个文件，避免右侧空白
    if (!selectedFileId.value && page.records.length > 0) {
      await selectFile(page.records[0]);
    }
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    filesLoading.value = false;
  }
}

async function selectFile(file: FileResult): Promise<void> {
  if (selectedFileId.value === file.id) {
    return;
  }
  stopPolling();
  selectedFileId.value = file.id;
  pathEndNode.value = null;
  await loadLineage();
}

async function loadLineage(): Promise<void> {
  const fileId = selectedFileId.value;
  if (!fileId) {
    // 选中项被清空：作废在途请求，避免它们回来又写进 lineage
    lineageSeq += 1;
    lineage.value = null;
    lineageFileId.value = '';
    lineageLoading.value = false;
    cancelLoaderHint();
    return;
  }
  const seq = (lineageSeq += 1);
  lineageLoading.value = true;
  scheduleLoaderHint();
  try {
    const data = await getLineage(fileId);
    if (seq !== lineageSeq) {
      return;
    }
    lineage.value = data;
    lineageFileId.value = fileId;
    syncPositionsWithLineage(fileId, data);
  } catch {
    if (seq === lineageSeq) {
      lineage.value = null;
      lineageFileId.value = '';
      // 失败提示已由接口层统一拦截处理
    }
  } finally {
    if (seq === lineageSeq) {
      lineageLoading.value = false;
      cancelLoaderHint();
    }
  }
}

/** 各文件上一次同步坐标时的节点集合签名：避免每次轮询都白扫一遍 localStorage */
const syncedNodeSignatures = new Map<string, string>();

/**
 * 把本地记住的节点坐标与刚取到的血缘对齐。
 *
 * <p>两件事：
 * <ol>
 *   <li>**恢复**：把该文件上次存下的坐标装进内存表，供链图渲染时优先使用；</li>
 *   <li>**清理**：任务 ID 是一次性的，重新触发会产生新 ID、旧记录永远匹配不上。
 *       血缘里已不存在的任务 ID，其坐标记录直接删掉，避免死数据越积越多。</li>
 * </ol>
 *
 * <p>只在节点集合真的变化时才做，避免每 2 秒的轮询反复扫 localStorage。
 */
function syncPositionsWithLineage(fileKey: string, data: Lineage | null): void {
  if (!data) {
    return;
  }
  const signature = data.nodes.map((node) => node.taskId).join(',');
  if (syncedNodeSignatures.get(fileKey) === signature) {
    return;
  }
  syncedNodeSignatures.set(fileKey, signature);

  prunePositions(fileKey, new Set(data.nodes.map((node) => node.taskId)));

  let map = nodePositions.get(fileKey);
  if (!map) {
    map = new Map<string, NodePosition>();
    nodePositions.set(fileKey, map);
  }
  // 本地记录只用作「没算过的新节点的初始值」，已有的内存值优先（可能刚被拖动过）
  for (const [taskId, pos] of readPositions(fileKey)) {
    if (!map.has(taskId)) {
      map.set(taskId, pos);
    }
  }
}

/** 链图选中路径变化：末端节点决定「下一个可触发的环节」 */
function onPathSelect(node: LineageNode | null): void {
  pathEndNode.value = node;
}

/** 详情抽屉：被查看的那次运行（null = 关闭） */
const detailNode = ref<LineageNode | null>(null);
const detailVisible = ref(false);

/** 当前选中文件的名字（抽屉头部展示；找不到时回落 `—`） */
const selectedFileName = computed(
  () => files.value.find((file) => file.id === selectedFileId.value)?.fileName ?? '—',
);

/** 当前选中文件的**文件对象 ID**（原文件下载地址 `GET /files/{fileId}` 用；取不到时为空串） */
const selectedFileObjectId = computed(
  () => files.value.find((file) => file.id === selectedFileId.value)?.fileId ?? '',
);

/** 当前选中文件的那条记录：数据源判据与徽标文案都从它取；未选中时为 null */
const selectedFile = computed(
  () => files.value.find((file) => file.id === selectedFileId.value) ?? null,
);

/**
 * 当前选中文件不能继续执行的原因文案；可以继续时为空串。
 *
 * <p>判据与文案都取类型层的 {@link fileStorageBlockReason}：文件栏徽标、链图卡片与右侧入口
 * 读到的是同一份结论。数据源 ID 未下发时按"无这条信息"处理，不拦触发。
 */
const storageBlockReason = computed(() => fileStorageBlockReason(selectedFile.value));

/** 徽标悬停提示：展示名 + 不能继续执行时的原因（可以继续时只给展示名） */
function fileStoreTitle(file: FileResult): string {
  // 徽标标签是存储类型，提示里说清具体数据源实例
  const name = file.storageSourceName ?? file.storageType;
  const prefix = name ? `存储：${name}` : '';
  const reason = fileStorageBlockReason(file);
  if (!prefix) {
    return reason;
  }
  return reason ? `${prefix} · ${reason}` : prefix;
}

/** 打开详情抽屉：只记下被点的节点，不动选中路径、也不触发轮询 */
function openDetail(node: LineageNode): void {
  detailNode.value = node;
  detailVisible.value = true;
}

/**
 * 打开「触发下一环节」确认弹窗。
 *
 * @param source 卡片上点按钮的那个节点（来自图组件的 trigger 事件）。
 *   **可选**：画布右下角的按钮不带节点参数，沿用当前选中的路径末端。
 *
 * <p>**带节点时以它为准**：分叉点就是**被点那张卡的产物**，同环节多个产物各自成链。
 * 点击瞬间页面这份选中值可能还没更新完，故直接记下传进来的节点，
 * 后续确认与弹窗展示都用它，不回头读选中值。
 *
 * <p>不带节点（右下角按钮）时用当前选中的路径末端，没有产物就不打开。
 */
function openTrigger(source?: LineageNode): void {
  const node = source ?? pathEndNode.value;
  if (!node?.productId) {
    return;
  }
  triggerSource.value = node;
  triggerVisible.value = true;
}

/**
 * 触发解析：链路的第一个环节。
 *
 * <p>解析没有上游产物，不需要「先选路径末端」——直接调接口即可。
 * 这是全新文件唯一的入口，缺了它整条链路无法从零启动。
 */
async function onTriggerParse(): Promise<void> {
  if (!selectedFileId.value) {
    return;
  }
  triggering.value = true;
  try {
    await addStageTrigger(selectedFileId.value, 'PARSE', {});
    startPolling();
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    triggering.value = false;
  }
}

/**
 * 确认触发：以**被点那张卡的产物**为分叉点，调用该环节的触发接口。
 *
 * <p>接口是异步的（只返回任务 ID），触发后开始轮询执行树，直到新任务进入终态。
 * 分叉点取 {@link triggerSource}（点按钮那一刻记下的节点），不读选中值 ——
 * 轮询刷新或用户改选都不该把分叉点换掉。
 */
async function onTriggerConfirm(strategyVersionId: string | null): Promise<void> {
  const stage = triggerStage.value;
  const upstream = triggerSource.value;
  if (!stage || !upstream) {
    return;
  }
  triggering.value = true;
  try {
    await addStageTrigger(selectedFileId.value, stage, {
      strategyVersionId,
      upstreamProductId: upstream.productId,
    });
    triggerVisible.value = false;
    startPolling();
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    triggering.value = false;
  }
}

/**
 * 轮询执行树：任务在跑时节点状态会变，跑完自动停。
 *
 * <p>**先立刻拉一次，再定时拉**：触发接口只登记任务、不同步执行，节点的出现完全依赖
 * 这次查询。等下第一个 tick 的话，任务在窗口内跑完时用户会看到"点了没反应，然后直接
 * 蹦出一个已完成的节点"——排队与进行中的过程整个被跳过。
 *
 * <p>停止条件用「是否还有未进入终态的任务」（类型层的 isTaskPending）：
 * 任务可能长期处于 QUEUED，也可能直接进入 PARTIAL_SUCCESS / FAILED，
 * 只盯 RUNNING 会漏判（PARTIAL_SUCCESS 被漏判时轮询永不停止）。
 *
 * <p>未知状态按未完成处理，最多轮询 MAX_TICKS 次后放弃，避免无限打接口。
 */
function startPolling(): void {
  stopPolling();
  let ticks = 0;
  const MAX_TICKS = 150;
  /** 拉一次并判断是否可以收工 */
  const refresh = (): void => {
    void loadLineage().then(() => {
      if (!hasPendingTask()) {
        stopPolling();
      }
    });
  };
  refresh();
  pollTimer = window.setInterval(() => {
    ticks += 1;
    if (ticks > MAX_TICKS) {
      stopPolling();
      return;
    }
    refresh();
  }, 2000);
}

/** 是否还有未进入终态的环节任务 */
function hasPendingTask(): boolean {
  return (lineage.value?.nodes ?? []).some((node) => isTaskPending(node.status));
}

function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
}

/** 文件扩展名（大写，去点）；取不到时回落成 FILE 而不是空徽标 */
function fileExt(fileName: string): string {
  const dot = fileName.lastIndexOf('.');
  if (dot < 0 || dot === fileName.length - 1) {
    return 'FILE';
  }
  return fileName.slice(dot + 1).toUpperCase();
}

function formatSize(bytes: number | null): string {
  if (bytes === null) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes}B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(0)}K`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)}M`;
}

/** 提交时间：只到分钟，与链图节点的时间格式统一（MM-DD HH:mm） */
function formatTime(value: string): string {
  const normalized = value.replace('T', ' ');
  return normalized.length >= 16 ? normalized.slice(5, 16) : normalized;
}

onMounted(() => {
  void loadKnowledgeBase();
  void loadStrategyBindings();
  void loadFiles();
});

// 离开页面必须停掉轮询：定时器不得继续打接口
onUnmounted(() => {
  stopPolling();
  cancelLoaderHint();
});
</script>

<style scoped lang="css">
.stage-page {
  display: flex;
  flex-direction: column;

  /* 无自带页头（面包屑在顶栏），body 直接占满 */
  height: calc(100vh - 128px);
  min-height: 420px;
}

.stage-body {
  display: flex;
  flex: 1;
  gap: 14px;
  min-height: 0;
}

.stage-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--kb-line);
  border-radius: var(--kb-radius);
  background: linear-gradient(180deg, rgb(255 255 255 / 3%), rgb(255 255 255 / 1.2%));
}

.stage-panel-head {
  display: flex;
  flex: none;
  gap: 9px;
  align-items: center;

  /* 定高：标题行只占标题本身的高度，把纵向空间让给文件列表 */
  min-height: 32px;
  padding: 5px 15px;

  /* 自己裁剪 + 禁止换行："文件"在栏收窄时被切掉，而不是被挤成竖排两行 */
  overflow: hidden;
  white-space: nowrap;
  border-bottom: 1px solid var(--kb-line);
}

.stage-panel-title {
  /* 不参与收缩：不然窄栏下文字会被压着一字一行 */
  flex: none;
  font-size: 13px;
  font-weight: 600;
}

/* 左：文件列表 */

/* 文件栏：不裁剪自身 —— 折叠开关骑在右边界上，光晕要能溢出到栏外 */
.stage-files {
  position: relative;
  width: 262px;
  flex: none;
  overflow: visible;
  transition: width 0.22s ease;
}

/*
 * 收起：宽度要放得下"列表左偏移 + 徽标 + 右侧留白"。
 * 徽标 42px + 列表 padding-left 12px + 行 padding-left 11px = 65px，加右侧留白与边框 ≈ 78px。
 */
.stage-files-collapsed {
  width: 78px;
}

/*
 * 折叠开关：与左侧菜单栏同一款"激光缝"，骑在文件栏右边界垂直中点上。
 *
 * <p>**定位**：`right: -3px`（= 宽度 6px 的一半）压在那条边界线上，
 * `top: 50%` + `translateY(-50%)` 落在垂直中点。只依赖栏的右边界。
 *
 * <p>两种状态**同色系（青蓝）**，只靠三角方向区分：展开朝左、收起朝右。
 *
 * <p>发光靠 `box-shadow` 外溢，外层**不能有 `overflow: hidden`**
 * —— `.stage-files` 是 `.stage-panel`（有 overflow: hidden）的自身，
 * 按钮定位在它内部、只探出 3px，不会被裁。
 */
.stage-collapse {
  position: absolute;
  top: 50%;
  right: -3px;
  z-index: 3;
  width: 6px;
  height: 40px;
  padding: 0;
  border: none;
  border-radius: 3px;
  background: linear-gradient(
    180deg,
    rgb(52 211 153 / 0%),
    rgb(52 211 153 / 30%),
    rgb(45 212 191 / 85%),
    rgb(52 211 153 / 30%),
    rgb(52 211 153 / 0%)
  );
  box-shadow: 0 0 10px rgb(52 211 153 / 50%);
  cursor: pointer;
  transform: translateY(-50%);
  transition:
    height 0.24s ease,
    box-shadow 0.24s ease;
}

/* 缝里的三角：提示"点它可以朝这个方向收/展"。实心三角在 6px 宽里比描边箭头清晰 */
.stage-collapse::after {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 0;
  height: 0;
  border-top: 3px solid transparent;
  border-right: 4px solid var(--kb-bg-1);
  border-bottom: 3px solid transparent;
  content: '';
  transform: translate(-38%, -50%);
  transition:
    border 0.24s ease,
    transform 0.24s ease;
}

/* 悬停：光缝拉长加亮 */
.stage-collapse:hover {
  height: 52px;
  box-shadow: 0 0 20px rgb(52 211 153 / 85%);
}

/* 收起态：不换色，只翻三角 + 光晕略强 */
.stage-collapse.is-collapsed {
  box-shadow: 0 0 14px rgb(52 211 153 / 65%);
}

.stage-collapse.is-collapsed::after {
  border-right: none;
  border-left: 4px solid var(--kb-bg-1);
  transform: translate(-62%, -50%);
}

.stage-collapse.is-collapsed:hover {
  box-shadow: 0 0 20px rgb(52 211 153 / 85%);
}

.stage-file-list {
  flex: 1;
  min-height: 0;

  /*
   * 两个方向都显式控制：只写 `overflow-y: auto` 时，`overflow-x` 会按计算值变成
   * `auto`（规范里 visible + 非 visible 的组合规则），于是收起态一旦内容比栏宽，
   * 就会冒出横向滚动条。这里明确 x 方向裁掉、只留纵向滚动。
   */
  overflow: hidden auto;
  padding: 6px 12px 6px 6px;
}

.stage-file {
  position: relative;
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 5px 11px;
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  transition: padding 0.22s ease;
}

/*
 * 徽标两态同尺寸：展开/收起都用 48px，收缩时图标不跳变。
 * 行内容宽度同步让出 8px，文件名仍在栏内换行。
 */
.stage-file-badge {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  font-size: 11px;
}

.stage-file:hover {
  background: rgb(255 255 255 / 4%);
}

.stage-file-on {
  border-color: rgb(52 211 153 / 35%);
  background: var(--kb-tint);
}

/* 格式徽标见 FileExtBadge 组件（按格式家族配色） */

/*
 * 行悬停时让徽标弹一下。徽标是独立 scoped 组件、内部类名选不中，父组件在模板里
 * 给它挂一个自己的类（stage-file-badge，落在子组件根元素上），选它即可；
 * 只置一个可继承的自定义属性，动画细节仍由徽标组件自己定义。
 * 鼠标停在整行任意处都有反馈，不必精确停在 48px 的徽标上。
 */
.stage-file:hover .stage-file-badge {
  --ext-hover: 1;
}

/*
 * 主内容两行：文件名在上、大小与时间在下。
 *
 * <p>宽度按“栏宽 − 边框/内边距/徽标/间距 − 右侧留白”钉死：
 * 262 − 2 − 18 − 22 − 48 − 6 = 166px。展开/收起都用 48px 徽标。
 */
.stage-file-main {
  display: flex;
  width: 166px;
  flex: none;
  flex-direction: column;
  gap: 1px;
  overflow: hidden;
  opacity: 1;
  transition: opacity 0.22s ease;
}

.stage-file-name {
  /* 完整显示文件名：允许折行，长串（无空格）也能断，不截断省略号（行高随内容增长） */
  overflow-wrap: anywhere;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
}

.stage-file-meta-text {
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
}

/*
 * 存储类型徽标：与行内大小文字同为 10px 的小号标签，宽度随文字。
 *
 * <p>自己的字号由 10px 显式写定、不继承：继承来的字号会随行内其它文字变化。
 * 两档配色只差一档明度：与当前写入后端一致的一档用主色，不一致的一档用告警色。
 */
.stage-file-store {
  display: inline-flex;
  flex: none;
  align-self: flex-start;
  align-items: center;
  padding: 1px 7px;
  border-radius: 999px;
  background: rgb(255 255 255 / 6%);
  color: var(--kb-text-3);
  font-size: 10px;
  white-space: nowrap;
}

.stage-file-store[data-tone='same'] {
  background: rgb(52 211 153 / 12%);
  color: var(--kb-primary);
}

.stage-file-store[data-tone='other'] {
  background: rgb(251 191 36 / 14%);
  color: var(--kb-warn);
}

.stage-pager {
  display: flex;
  flex: none;
  justify-content: center;
  padding: 8px 0;
  border-top: 1px solid var(--kb-line);
}

/* 右：链图 */
.stage-chain-wrap {
  flex: 1;
  min-width: 0;
  position: relative;
}

.stage-canvas {
  position: relative;
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;

  /* 画布自身不滚动/不留白：平移缩放交给 Vue Flow，工具按钮也在它内部右上角 */
  padding: 0 0 8px;
}

/* 刷新中：轻微降透明度而不是卸载重建，切文件时不会"闪一下" */
.stage-canvas > .is-refreshing {
  opacity: 0.85;
  transition: opacity 0.12s;
}

/* 状态色点：图例仍在使用（节点的状态色在 ChainNode 组件内） */
.stage-nd {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
}

.tone-ok {
  background: var(--kb-ok);
}

.tone-partial {
  background: var(--kb-warn);
}

.tone-run {
  background: var(--kb-primary);
}

.tone-fail {
  background: var(--kb-danger);
}

.tone-hit {
  background: var(--kb-warn);
}

.stage-legend {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  padding: 10px 15px;
  border-top: 1px solid var(--kb-line);
  color: var(--kb-text-3);
  font-size: 11px;
}

.stage-legend-item {
  display: flex;
  gap: 6px;
  align-items: center;
}

.stage-legend-hit {
  color: var(--kb-warn);
}

.stage-trigger-btn {
  border: none;
  background: linear-gradient(135deg, var(--kb-primary), var(--kb-primary-2));
  color: var(--kb-btn-text);
  font-size: 12px;
  font-weight: 650;
}

/* 全新文件：链图为空，居中给出「触发解析」入口（解析无上游，是全链路唯一不需要选路径的环节） */
.stage-start {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  justify-content: center;
}

.stage-start-title {
  color: var(--kb-text-2);
  font-size: 13px;
}

.stage-start-hint {
  margin-bottom: 10px;
  color: var(--kb-text-3);
  font-size: 12px;
}

/* 入口被拦的原因：与提示行同宽居中，长文案折行而不是把按钮顶偏 */
.stage-start-block {
  max-width: 320px;
  color: var(--kb-warn);
  font-size: 12px;
  text-align: center;
}

.stage-empty {
  flex: 1;
  padding: 60px 0;
  color: var(--kb-text-3);
  font-size: 13px;
  text-align: center;
}

/* 右栏画布内的空态居中；.stage-canvas 是右栏独有祖先，左栏文件区那两处不受影响 */
.stage-canvas > .stage-empty {
  display: flex;
  align-items: center; /* 垂直居中 */
  justify-content: center; /* 水平居中 */
  padding: 0; /* 顶掉 .stage-empty 的 60px 上留白，否则会被顶偏 */
}

.stage-panel > .stage-empty {
  display: flex;
  align-items: center; /* 垂直居中 */
  justify-content: center; /* 水平居中 */
  padding: 0 0 78px; /* 顶掉 .stage-empty 的 60px 上留白，否则会被顶偏 */
}

/* ==================== 收起态（放最后：特异性高于上面的基础样式）==================== */

/* ==================== 收起态：同一套 DOM，只把文字收掉 ==================== */

/*
 * 收起态**不换 DOM**：文字块移出文档流并淡出，只保留 48px 格式徽标；
 * 满页时行高由列表剩余高度均分，徽标始终落在行中心。
 */
.stage-file-list-icons {
  padding: 6px 8px 6px 6px;
}

/* 收起时行不需要悬停底色与选中描边：那里没有内容承载它们 */
.stage-file-list-icons .stage-file {
  /* 不能用 center：收起瞬间面板仍是宽态，图标会先跳到整行中心再随宽度收回。
     保持左对齐，只把 padding-left 从 11px 过渡到 8px，图标才连续移动。 */
  padding: 6px 0 6px 8px;
  background: none;
  border-color: transparent;
}

/* 选中态：比 48px 徽标大一圈，框与图标同中心 */
.stage-file-list-icons .stage-file-on::before {
  position: absolute;
  top: 50%;
  left: 32px;
  width: 60px;
  height: 60px;
  border-radius: 16px;
  background: var(--kb-tint);

  /* 32px = padding-left 8 + 48px 徽标的一半；框与图标同中心 */
  transform: translate(-50%, -50%);
  content: '';
}

/*
 * 收起时的文字块移出文档流：只淡出但保留高度会把每一行撑到文字高度，
 * 图标之间因此出现大空隙；绝对定位后行高只由徽标与内边距决定。
 */
.stage-file-list-icons .stage-file-main {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

/*
 * 收起态的空态文字：与文件行同一套处理（淡出而不是收窄），
 * 78px 宽放不下「该知识库还没有文件」，折行后会压到右边界外。
 */
.stage-files-collapsed > .stage-empty {
  opacity: 0;
}

/*
 * 满页（8 条）时把列表剩余高度均分给文件卡：
 * 卡片本身仍按内容排版，只拉伸行高，底部不再留一块空白。
 */
.stage-file-list-full {
  display: flex;
  flex-direction: column;
}

.stage-file-list-full .stage-file {
  flex: 1 1 0;
}
</style>
