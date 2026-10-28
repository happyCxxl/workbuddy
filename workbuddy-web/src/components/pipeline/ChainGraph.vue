<template>
  <div class="chain-graph">
    <VueFlow
      :nodes="graphNodes"
      :edges="graphEdges"
      :node-types="nodeTypes"
      :nodes-connectable="false"
      :nodes-draggable="true"
      :elements-selectable="true"
      :zoom-on-scroll="true"
      :pan-on-drag="true"
      :min-zoom="0.3"
      :max-zoom="1.6"
      :fit-view-on-init="false"
      :default-viewport="{ x: 0, y: 0, zoom: 1 }"
      @node-click="onNodeClick"
      @init="onInit"
    >
      <Background :gap="18" :size="1" pattern-color="rgb(255 255 255 / 7%)" />
      <!-- 控件固定在右上角；底板样式（不透明 + 模糊）见 styles/chain-graph.css：
           节点从下面拖过时不会把它压花 -->
      <Controls position="top-right" :show-interactive="false" />
    </VueFlow>

    <div v-if="!hasNodes" class="chain-graph-empty">该文件还没有任何环节运行过</div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import { VueFlow, getRectOfNodes, useVueFlow } from '@vue-flow/core';
import type { GraphNode } from '@vue-flow/core';

import ChainNode from '@/components/pipeline/ChainNode.vue';
import { isTaskPending } from '@/types/pipeline';
import type { Lineage, LineageNode, NodePosition, NodePositionStore } from '@/types/pipeline';
import { writePosition } from '@/utils/chain-layout-storage';

import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import '@vue-flow/controls/dist/style.css';
// Vue Flow 的暗色适配放全局样式：它生成的 DOM 不受本组件 scoped 约束
import '@/styles/chain-graph.css';

/**
 * 布局常量，与 ChainNode 的样式一一对应（改样式时同步这里）。
 *
 * <p>**注意量法**：节点在 Vue Flow 画布里会被缩放，`getBoundingClientRect()` 给的是
 * 屏幕像素，必须除以 viewport 缩放才是图坐标。
 *
 * <ul>
 *   <li>{@code width: 320} 对应 ChainNode 的 `width`</li>
 *   <li>列距 36 → 相邻列间距 356，够放下卡片与连接桩</li>
 *   <li>{@code NODE_ROW_HEIGHT: 228} = 卡片 214 + 上下留白 14，够放下连接桩与发光外圈</li>
 * </ul>
 *
 * <p>**卡片尺寸 320×214**：所有状态恒等 —— 改这两个常量时必须同步改 ChainNode.vue 的 CSS。
 */
const NODE_WIDTH = 320;
const COLUMN_GAP = 36;
const ROW_GAP = 24;
const FIRST_COLUMN_X = 20;
const FIRST_ROW_Y = 20;
/** 行距用的固定节点高度，与 ChainNode 的高度一致（214 + 14px 留白）。
 *  卡片各状态尺寸恒定，同列后续节点不会因内容多少跳位。 */
const NODE_ROW_HEIGHT = 228;
/** 一个行槽的步长 */
const SLOT = NODE_ROW_HEIGHT + ROW_GAP;

const props = defineProps<{
  lineage: Lineage | null;
  /** 当前文件结果 ID：位置按文件分开记忆，切回来能恢复用户摆好的布局 */
  fileKey: string;
  /** 各文件的节点坐标记忆（页面持有，跨文件切换持久） */
  positionStore: NodePositionStore;
  /** 命中知识库绑定策略的版本串集合（name-version） */
  boundVersions: Set<string>;
  /**
   * 当前文件不能继续执行的原因文案（可以继续时为空串）。
   *
   * <p>卡片只负责显示：判据与文案都由页面按数据源 ID 与显示名给出，图组件不自己拼。
   */
  storageBlockReason: string;
}>();

const emit = defineEmits<{
  /** 用户选中某个节点：路径变化，末端节点用于触发下游 */
  select: [node: LineageNode | null];
  /** 用户点了卡片上的「触发下一环节」：页面据此打开触发确认弹窗 */
  trigger: [node: LineageNode];
  /** 用户点了卡片上的「详情」：页面据此打开解析详情抽屉 */
  detail: [node: LineageNode];
}>();

/** 节点类型注册表：普通常量，不要用 reactive（Vue Flow 会警告并反复重建） */
const nodeTypes = { chain: ChainNode };

const { getNodes, dimensions, setViewport, onNodeDragStop, updateNodeInternals } = useVueFlow();

/** 是否已自动适配过视口：只在首次建图时适配，之后不重置用户的视角 */
const didFitView = ref(false);

/**
 * 自适应视口的缩放下限。
 *
 * <p>**按宽度适配**：执行链是"深而窄"的树（11 节点图横跨 3 列 640px，纵向可铺 1131px）。
 * 按高度约束会把缩放压到 0.4 上下，卡片从 168px 缩成 68px、13px 正文变成 5px，读不了。
 * 按宽度适配时缩放只由"最宽一列是否放得下"决定（该图约 1.0），纵向超出就交给用户平移：
 * **可读优先，一屏看不完就滚**。
 */
const MIN_READABLE_ZOOM = 0.5;

/** 视口四周留白（像素），避免节点贴边 */
const FIT_PADDING = 24;

/**
 * 图初始化完成后按宽度适配视口。
 *
 * <p>不用 `fit-view-on-init` 属性：它按默认参数把整图（含高度）塞进画布，
 * 会把卡片缩到读不了。
 */
function onInit(): void {
  fitViewOnce();
}

/**
 * 标记「需要重新适配视口」。
 *
 * <p>切文件时新旧节点毫无交集，视口与缩放都按新图重算。
 * 这里只置个标记，真正适配由 hasNodes 的 watch 在渲染后触发。
 */
function requestFitView(): void {
  didFitView.value = false;
  void Promise.resolve().then(() => fitViewOnce());
}

/**
 * 按宽度适配一次；之后加入新节点不重置视角。
 *
 * <p>**缩放自己算，不用 `getTransformForBounds`**：那个函数按 width/height 双约束取较小值，
 * 传什么高度都绕不开"高度也是一等约束"这件事。按宽度适配的公式只有一行。
 *
 * <p>不放大（上限 1）：小图保持原尺寸，不会被撑开到出画布。
 */
function fitViewOnce(): void {
  if (didFitView.value) {
    return;
  }
  didFitView.value = true;

  const nodes = getNodes.value;
  const { width, height } = dimensions.value;
  if (nodes.length === 0 || width === 0 || height === 0) {
    return;
  }

  const bounds = getRectOfNodes(nodes);
  const available = Math.max(width - FIT_PADDING * 2, 1);
  const zoom = Math.min(1, Math.max(MIN_READABLE_ZOOM, available / bounds.width));

  /*
   * 纵向定位：图比视口**高**时贴顶；放得下时垂直居中。
   */
  const scaledHeight = bounds.height * zoom;
  const y =
    scaledHeight > height
      ? FIT_PADDING - bounds.y * zoom
      : (height - scaledHeight) / 2 - bounds.y * zoom;

  void setViewport({ x: FIT_PADDING - bounds.x * zoom, y, zoom });
}

const selectedTaskId = ref<string>('');

/** 该节点是否命中知识库绑定策略 */
function isHit(node: LineageNode): boolean {
  return node.strategyVersion !== null && props.boundVersions.has(node.strategyVersion);
}

/** 父 → 子 邻接表，用于回溯路径 */
const childrenMap = computed(() => {
  const map = new Map<string, string[]>();
  for (const edge of props.lineage?.edges ?? []) {
    const list = map.get(edge.fromTaskId) ?? [];
    list.push(edge.toTaskId);
    map.set(edge.fromTaskId, list);
  }
  return map;
});

/** 子 → 父 邻接表 */
const parentMap = computed(() => {
  const map = new Map<string, string>();
  for (const edge of props.lineage?.edges ?? []) {
    map.set(edge.toTaskId, edge.fromTaskId);
  }
  return map;
});

const nodeById = computed(() => {
  const map = new Map<string, LineageNode>();
  for (const node of props.lineage?.nodes ?? []) {
    map.set(node.taskId, node);
  }
  return map;
});

/** 从某节点回溯到根，返回路径上的任务 ID（按根→叶顺序） */
function tracePath(taskId: string): string[] {
  const path: string[] = [];
  const seen = new Set<string>();
  let current: string | undefined = taskId;
  while (current && !seen.has(current)) {
    seen.add(current);
    path.unshift(current);
    current = parentMap.value.get(current);
  }
  return path;
}

/**
 * 默认选中「最深的一条已完成路径」。
 *
 * <p>默认选中最有进展的那条路径末端；用户点其它节点后按用户的选择走。
 */
function findDeepestPath(): string[] {
  const nodes = props.lineage?.nodes ?? [];
  if (nodes.length === 0) {
    return [];
  }
  const leaves = nodes.filter((node) => (childrenMap.value.get(node.taskId) ?? []).length === 0);
  let best: string[] = [];
  for (const leaf of leaves) {
    const path = tracePath(leaf.taskId);
    if (path.length > best.length) {
      best = path;
    }
  }
  return best;
}

/** 选中路径上的任务 ID 集合 */
const pathTaskIds = computed(() => new Set(tracePath(selectedTaskId.value)));

/** 节点深度（列号）：按血缘取最长路径，保证子节点永远落在父节点右侧 */
const columnOf = computed(() => {
  const depth = new Map<string, number>();
  const nodes = props.lineage?.nodes ?? [];
  for (const node of nodes) {
    depth.set(node.taskId, 0);
  }
  // 迭代求解（Bellman-Ford 式松弛），避免深链递归爆栈
  for (let round = 0; round < nodes.length; round += 1) {
    let changed = false;
    for (const edge of props.lineage?.edges ?? []) {
      const from = depth.get(edge.fromTaskId);
      const to = depth.get(edge.toTaskId);
      if (from === undefined || to === undefined || to >= from + 1) {
        continue;
      }
      depth.set(edge.toTaskId, from + 1);
      changed = true;
    }
    if (!changed) {
      break;
    }
  }
  return depth;
});

/**
 * 当前文件的坐标表：**直接引用页面持有的那一份**，不内部另存。
 *
 * <p>坐标的归属由 `fileKey` 决定，不依赖"当前渲染到哪一步"。
 */
const settledPositions = computed<Map<string, NodePosition>>(() => {
  let map = props.positionStore.get(props.fileKey);
  if (!map) {
    map = new Map<string, NodePosition>();
    props.positionStore.set(props.fileKey, map);
  }
  return map;
});

/** 各文件是否已适配过视口：每个文件各自适配一次 */
const fittedFiles = new Set<string>();

/**
 * 按血缘算一次完整的树形布局（纯函数，与刷新次数无关）。
 *
 * <p>三步，缺一不可：
 * <ol>
 *   <li>**叶子按列占位**：同列叶子从第 1 行开始找第一个不冲突的行
 *       （同列不得出现相同坐标）；</li>
 *   <li>**父节点取子节点跨度的中点**：分叉线条从中点散开，
 *       同源多条边的垂直段分散在不同 x 上；</li>
 *   <li>**父节点也要避让**：中点落点若撞上本列其它节点，就近上下挪一个槽位。</li>
 * </ol>
 *
 * @return 每个节点的布局坐标
 */
function layoutTree(allNodes: LineageNode[]): Map<string, { x: number; y: number }> {
  const result = new Map<string, { x: number; y: number }>();
  if (allNodes.length === 0) {
    return result;
  }

  const depth = columnOf.value;
  const xOf = (id: string): number =>
    FIRST_COLUMN_X + (depth.get(id) ?? 0) * (NODE_WIDTH + COLUMN_GAP);
  /** 列内已占用区间：[y, y + NODE_ROW_HEIGHT] */
  const occupied = new Map<number, Array<[number, number]>>();

  const collides = (column: number, y: number): boolean =>
    (occupied.get(column) ?? []).some(
      ([start, end]) => y < end + ROW_GAP && y + NODE_ROW_HEIGHT + ROW_GAP > start,
    );

  const place = (id: string, column: number, y: number): { x: number; y: number } => {
    const list = occupied.get(column) ?? [];
    list.push([y, y + NODE_ROW_HEIGHT]);
    occupied.set(column, list);
    const pos = { x: xOf(id), y };
    result.set(id, pos);
    return pos;
  };

  /** 给某列找一个不冲突的 y：从第 1 行起逐槽位试 */
  const firstFreeY = (column: number): number => {
    for (let slot = 0; slot < allNodes.length + 1; slot += 1) {
      const y = FIRST_ROW_Y + slot * SLOT;
      if (!collides(column, y)) {
        return y;
      }
    }
    return FIRST_ROW_Y;
  };

  /**
   * 父节点落点：取子节点跨度的中点，并保证**不与任何子节点同高**。
   *
   * <p>必须与所有子节点错开半个行槽：同高会让边退化成一条穿过节点的直线
   * （父节点落在子节点 y 上，两点之间没有垂直落差）。
   */
  const fitY = (column: number, ideal: number, childYs: number[]): number => {
    let y = ideal;
    if (childYs.some((childY) => Math.abs(childY - y) < 1)) {
      y = ideal - SLOT / 2;
    }
    if (!collides(column, y)) {
      return y;
    }
    for (let step = 1; step <= allNodes.length; step += 1) {
      const up = y - step * SLOT;
      if (
        up >= FIRST_ROW_Y &&
        !collides(column, up) &&
        !childYs.some((cy) => Math.abs(cy - up) < 1)
      ) {
        return up;
      }
      const down = y + step * SLOT;
      if (!collides(column, down) && !childYs.some((cy) => Math.abs(cy - down) < 1)) {
        return down;
      }
    }
    return y;
  };

  // ① 叶子先占位（只算叶子：有子节点的节点在 ② 里按子节点跨度定位）
  const leaves = allNodes.filter((node) => (childrenMap.value.get(node.taskId) ?? []).length === 0);
  for (const leaf of leaves) {
    const column = depth.get(leaf.taskId) ?? 0;
    place(leaf.taskId, column, firstFreeY(column));
  }

  // ② 自深到浅给父节点定位
  const rest = [...allNodes]
    .filter((node) => (childrenMap.value.get(node.taskId) ?? []).length > 0)
    .sort((a, b) => (depth.get(b.taskId) ?? 0) - (depth.get(a.taskId) ?? 0));
  for (const node of rest) {
    const column = depth.get(node.taskId) ?? 0;
    const childYs = (childrenMap.value.get(node.taskId) ?? [])
      .map((childId) => result.get(childId))
      .filter((pos): pos is { x: number; y: number } => pos !== undefined)
      .map((pos) => pos.y);
    const ideal =
      childYs.length > 0 ? (Math.min(...childYs) + Math.max(...childYs)) / 2 : firstFreeY(column);
    place(node.taskId, column, fitY(column, ideal, childYs));
  }

  // ③ 兜底：血缘异常（环、缺边）而没落位的节点
  for (const node of allNodes) {
    if (!result.has(node.taskId)) {
      const column = depth.get(node.taskId) ?? 0;
      place(node.taskId, column, firstFreeY(column));
    }
  }
  return result;
}

/**
 * 取节点最终坐标：**已在图上的节点坐标永不改动**，只给新节点落位。
 *
 * <p>这样同时满足两点：拖动结果不被覆盖；新节点按树形布局插到父节点跨度内。
 */
function resolvePosition(
  node: LineageNode,
  layout: Map<string, { x: number; y: number }>,
): { x: number; y: number } {
  const known = settledPositions.value.get(node.taskId);
  if (known) {
    return known;
  }
  const computed = layout.get(node.taskId) ?? { x: FIRST_COLUMN_X, y: FIRST_ROW_Y };
  settledPositions.value.set(node.taskId, computed);
  // 布局结果也落本地：刷新后沿用同一套坐标
  writePosition(props.fileKey, node.taskId, computed);
  return computed;
}

/**
 * 清掉「当前文件里已经消失的节点」的坐标。
 *
 * <p>只在**确有交集**时清理：切文件的那一瞬间 `selectedFileId` 已经变了、`lineage`
 * 还是旧文件的（接口还没返回），此时若按新文件清理，会把旧文件的坐标全删掉。
 * 一个都对不上就说明手上这份 lineage 不属于当前文件，直接不动。
 */
function dropStalePositions(allNodes: LineageNode[]): void {
  if (allNodes.length === 0) {
    return;
  }
  const ids = new Set(allNodes.map((node) => node.taskId));
  const hasOverlap = [...settledPositions.value.keys()].some((key) => ids.has(key));
  if (!hasOverlap) {
    return;
  }
  for (const key of [...settledPositions.value.keys()]) {
    if (!ids.has(key)) {
      settledPositions.value.delete(key);
    }
  }
}

/**
 * 待适配视口的文件：切过去的那一刻手上还是上一个文件的节点，
 * 这时算出来的缩放/平移对应的是旧图；记下文件 ID，等这个文件的血缘到手再适配。
 */
let pendingFitKey = '';

/** 切文件：登记待适配的文件，并先按住适配（窗口期里别拿旧图算视口） */
watch(
  () => props.fileKey,
  (key) => {
    pendingFitKey = key;
    didFitView.value = true;
  },
);

const graphNodes = computed(() => {
  const allNodes = props.lineage?.nodes ?? [];
  dropStalePositions(allNodes);
  const layout = layoutTree(allNodes);
  return allNodes.map((node) => ({
    id: node.taskId,
    type: 'chain',
    draggable: true,
    position: resolvePosition(node, layout),
    data: {
      node,
      onPath: pathTaskIds.value.has(node.taskId),
      hit: isHit(node),
      outCount: (childrenMap.value.get(node.taskId) ?? []).length,
      // 数据源不一致的原因：本次计算取一次，节点按它禁用触发入口并显示原因
      storageBlockReason: props.storageBlockReason,
      // 卡片上「触发下一环节」的回调：由本组件注入并转成 trigger 事件上抛，
      // 并把**被点的那张卡的节点**一起交出去（下游按它的产物分叉）。
      // 这样自定义节点不必自己想办法 emit（Vue Flow 的节点是它内部渲染的）
      onTrigger: () => emit('trigger', node),
      // 卡片上「详情」的回调：同样把被点的节点交出去，页面按它的 taskId 取详情
      onDetail: () => emit('detail', node),
    },
  }));
});

/**
 * 边的视觉语义。
 *
 * <p>**全部为流动虚线**：靠颜色与粗细区分状态，不靠"实线/虚线"。
 *
 * <ul>
 *   <li>`run`：下游还在跑 → 主色、最粗</li>
 *   <li>`done`：两端都已结束 → 主色</li>
 *   <li>`idle`：其它（失败、未开始）→ 弱化</li>
 * </ul>
 *
 * <p>`animated` 会加 `stroke-dasharray` 与 `dashdraw` 流动动画；`styleDash` 只负责虚线
 * 疏密，缺了它未动画的边会是实线。
 */
const graphEdges = computed(() =>
  (props.lineage?.edges ?? []).map((edge) => {
    const onPath = pathTaskIds.value.has(edge.fromTaskId) && pathTaskIds.value.has(edge.toTaskId);
    const source = nodeById.value.get(edge.fromTaskId);
    const target = nodeById.value.get(edge.toTaskId);
    const sourceDone = !isTaskPending(source?.status);
    const targetPending = isTaskPending(target?.status);
    let state: 'run' | 'done' | 'idle' = 'idle';
    if (targetPending && sourceDone) {
      state = 'run';
    } else if (sourceDone && !targetPending) {
      state = 'done';
    }
    // 按该边在源节点出边中的次序选源桩：起点分散，多次触发不叠在一起
    const outIndex = outIndexOf.value.get(`${edge.fromTaskId}-${edge.toTaskId}`) ?? 0;
    return {
      id: `${edge.fromTaskId}-${edge.toTaskId}`,
      source: edge.fromTaskId,
      target: edge.toTaskId,
      sourceHandle:
        (childrenMap.value.get(edge.fromTaskId) ?? []).length > 1 ? `out-${outIndex}` : undefined,
      type: 'smoothstep',
      animated: true,
      data: { state, onPath },
      style: edgeStyle(state, onPath),
    };
  }),
);

/** 边在源节点出边里的次序：用于选择源桩 */
const outIndexOf = computed(() => {
  const counters = new Map<string, number>();
  const index = new Map<string, number>();
  for (const edge of props.lineage?.edges ?? []) {
    const next = counters.get(edge.fromTaskId) ?? 0;
    index.set(`${edge.fromTaskId}-${edge.toTaskId}`, next);
    counters.set(edge.fromTaskId, next + 1);
  }
  return index;
});

/** 边样式：虚线疏密在 strokeDasharray，颜色/粗细表示状态 */
function edgeStyle(
  state: 'run' | 'done' | 'idle',
  onPath: boolean,
): Record<string, string | number> {
  if (state === 'idle') {
    return { stroke: 'var(--kb-line-strong)', strokeWidth: 1.2, strokeDasharray: '3 4' };
  }
  return {
    stroke: state === 'run' || onPath ? 'var(--kb-primary)' : 'rgb(52 211 153 / 65%)',
    strokeWidth: state === 'run' ? 2.2 : onPath ? 2 : 1.6,
    strokeDasharray: '6 4',
  };
}

const hasNodes = computed(() => (props.lineage?.nodes ?? []).length > 0);

function onNodeClick(event: { node: { id: string } }): void {
  selectedTaskId.value = event.node.id;
  emit('select', nodeById.value.get(event.node.id) ?? null);
}

/**
 * 数据换了一批（切文件/轮询刷新）时的选中处理。
 *
 * <p>**只在首次、以及选中的节点确实消失时**才自动选「最深路径末端」。
 * 不要每次刷新都做兜底改选：轮询每 2 秒一次，用户点选不能被改成别的节点
 * （用户点选是明确意图：指定从哪触发下游）。
 */
watch(
  () => props.lineage,
  (lineage) => {
    if (!lineage || lineage.nodes.length === 0) {
      selectedTaskId.value = '';
      emit('select', null);
      return;
    }
    const stillExists = lineage.nodes.some((node) => node.taskId === selectedTaskId.value);
    if (!stillExists) {
      // 选中的节点没了（首次进入、或切文件/换了一批）→ 选最有进展的那条路径末端
      const path = findDeepestPath();
      selectedTaskId.value = path.length > 0 ? path[path.length - 1] : '';
    }
    emit('select', nodeById.value.get(selectedTaskId.value) ?? null);
  },
  { immediate: true },
);

/**
 * 出度变化必须通知 Vue Flow 重算连接桩位置。
 *
 * <p>**出度变化必须通知 Vue Flow 重算**：Vue Flow 在节点挂载时缓存各连接桩的位置
 * （handleBounds）。每新增一次下游运行，源桩数量 +1、且**所有旧桩按百分比重新均布**，
 * 缓存不失效时边仍按旧坐标绘制，全挤在同一处。
 *
 * <p>缓存在下一帧才可能过期，所以延到渲染后再触发重算。
 */
const outDegreeSignature = computed(() =>
  (props.lineage?.nodes ?? [])
    .map((node) => `${node.taskId}:${(childrenMap.value.get(node.taskId) ?? []).length}`)
    .join(','),
);

let lastOutDegreeSignature = '';

watch(outDegreeSignature, (signature) => {
  if (signature === lastOutDegreeSignature) {
    return;
  }
  lastOutDegreeSignature = signature;
  const ids = (props.lineage?.nodes ?? []).map((node) => node.taskId);
  if (ids.length === 0) {
    return;
  }
  void nextTick(() => {
    updateNodeInternals(ids);
  });
});

// onInit 可能早于节点到位（此时图是空的），等首次有节点后再适配一次视口
watch(hasNodes, (has) => {
  if (has) {
    void Promise.resolve().then(() => fitViewOnce());
  }
});

/**
 * 新血缘到手：登记过待适配的文件到这一刻才适配，用的是**新图的节点**。
 *
 * <p>判据沿用"摆过布局的不动用户视角"：该文件坐标表非空（页面在数据到手时刚填好），
 * 或本次会话已为它适配过，就保持当前视口。
 */
watch(
  () => props.lineage,
  () => {
    const key = pendingFitKey;
    if (key === '' || key !== props.fileKey || !hasNodes.value) {
      return;
    }
    pendingFitKey = '';
    if (fittedFiles.has(key) || (props.positionStore.get(key)?.size ?? 0) > 0) {
      return;
    }
    fittedFiles.add(key);
    requestFitView();
  },
);

/**
 * 拖动结束：把**实际坐标**回写到落位表。
 *
 * <p>**必须做这一步**：落位表里存的是布局算出的坐标，不是用户拖动后的坐标。
 * 不回收的话，下一次重算（点一下节点就会触发一次）会把那份陈旧的布局坐标
 * 经 Vue Flow 的 Object.assign 覆盖回节点上。
 */
onNodeDragStop(({ node }: { node: GraphNode }) => {
  const { x, y } = node.computedPosition;
  if (Number.isFinite(x) && Number.isFinite(y)) {
    settledPositions.value.set(node.id, { x, y });
    // 立刻落本地：刷新页面后还能恢复用户摆好的布局。
    // 写失败（配额满/隐私模式）只影响"刷新后是否记得"，页面不受影响
    writePosition(props.fileKey, node.id, { x, y });
  }
});
</script>

<style scoped lang="css">
.chain-graph {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 320px;
}

/* 环节名画在节点上（见 ChainNode），不在这里做画布级标尺：
   标尺是屏幕坐标、节点是图坐标，平移缩放或拖动节点后必然错位 */
.chain-graph-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--kb-text-3);
  font-size: 13px;
}
</style>
