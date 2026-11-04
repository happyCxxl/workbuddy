import { computed, ref, type Ref } from 'vue';

import type { ModelCallItem, SessionEventItem } from '@/types/assistant';
import { durationLabel, formatClock, preview, prettyJson } from '@/utils/chat-format';

/** 轨迹节点：文本/思考增量不单独成条 */
export interface TrailNode {
  key: string;
  kind: 'user' | 'assistant' | 'tool' | 'call' | 'error';
  badge: string;
  title: string;
  detail?: string;
  time: string;
  /** 工具/步骤耗时（开始到结果的时间差） */
  duration?: string;
  startedMs?: number;
  endedMs?: number;
  /** 检查器附加信息：模型、token 等 */
  meta?: string;
  /** 工具调用 ID，用于精确关联 tool.result */
  callId?: string;
}

export interface TrailTurn {
  key: string;
  index: number;
  startedAt: string;
  startedMs: number;
  endedMs: number | null;
  status: string;
  statusLabel: string;
  duration: string;
  nodes: TrailNode[];
  /** 时间概览用的轮次时间窗 */
  spanStart: number;
  spanEnd: number;
}

interface TrajRow {
  key: string;
  height: number;
  turn: TrailTurn;
  node: TrailNode | null;
}

const TICK_STEPS = [
  1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000, 900000, 1800000, 3600000,
];
const TRAJ_HEADER_HEIGHT = 30;
const TRAJ_ROW_HEIGHT = 28;
const TRAJ_VIEWPORT = 560;

/** 轮次状态中文名 */
function trailStatusLabel(status: string): string {
  if (status === 'completed') return '完成';
  if (status === 'pending_stop') return '已停车';
  if (status === 'paused') return '等待审批';
  if (status === 'interrupted') return '已中断';
  if (status === 'aborted') return '中止';
  if (status === 'error') return '出错';
  return status;
}

/** 新轮次：turn/start 建一个分组 */
function newTrailTurn(index: number, event: SessionEventItem): TrailTurn {
  const at = Date.parse(event.created_at);
  return {
    key: `turn-${String(event.seq)}`,
    index,
    startedAt: formatClock(event.created_at),
    startedMs: Number.isNaN(at) ? 0 : at,
    endedMs: null,
    status: 'running',
    statusLabel: '进行中',
    duration: '',
    nodes: [],
    spanStart: Number.isNaN(at) ? 0 : at,
    spanEnd: Number.isNaN(at) ? 0 : at,
  };
}

/** 单事件归并为节点：文本/思考增量直接跳过 */
function appendTrailEvent(turn: TrailTurn, event: SessionEventItem): void {
  const time = formatClock(event.created_at);
  const at = Date.parse(event.created_at);
  if (event.type === 'user/message') {
    const content = String(event.payload['content'] ?? '');
    turn.nodes.push({
      key: `e${String(event.seq)}`,
      kind: 'user',
      badge: '你',
      title: preview(content),
      detail: content,
      time,
      startedMs: at,
      endedMs: at,
    });
    return;
  }
  if (event.type === 'assistant/message') {
    const content = String(event.payload['content'] ?? '').trim();
    const rawCalls = event.payload['tool_calls'];
    const calls = Array.isArray(rawCalls) ? rawCalls : [];
    if (!content && calls.length === 0) {
      return;
    }
    const names = calls
      .map((item) => String((item as Record<string, unknown>)['name'] ?? ''))
      .filter(Boolean);
    turn.nodes.push({
      key: `a${String(event.seq)}`,
      kind: 'assistant',
      badge: '助手',
      title: content ? preview(content) : `计划调用 ${names.join('、') || '工具'}`,
      detail: content || JSON.stringify(calls, null, 2),
      time,
      startedMs: at,
      endedMs: at,
    });
    return;
  }
  if (event.type === 'tool.call') {
    const name = String(event.payload['name'] ?? '');
    const args = String(event.payload['arguments'] ?? '');
    turn.nodes.push({
      key: `t${String(event.seq)}`,
      kind: 'tool',
      badge: '工具',
      title: `${name} ${preview(args, 80)}`,
      detail: prettyJson(args),
      time,
      startedMs: at,
      callId: String(event.payload['tool_call_id'] ?? ''),
    });
    return;
  }
  if (event.type === 'tool.result') {
    const name = String(event.payload['name'] ?? '');
    const callId = String(event.payload['tool_call_id'] ?? '');
    const node = [...turn.nodes]
      .reverse()
      .find((item) =>
        item.kind === 'tool' && callId ? item.callId === callId : item.title.startsWith(name),
      );
    if (!node) {
      return;
    }
    const error = event.payload['error'];
    node.endedMs = at;
    node.duration = durationLabel(node.startedMs ?? at, at);
    if (error) {
      node.kind = 'error';
      node.badge = '失败';
      node.title = `${name} 失败：${preview(String(error), 100)}`;
      node.detail = String(error);
      return;
    }
    node.title = `${node.title} → ${preview(String(event.payload['result'] ?? ''), 80)}`;
    return;
  }
  if (event.type === 'error') {
    const message = String(event.payload['message'] ?? '');
    turn.nodes.push({
      key: `x${String(event.seq)}`,
      kind: 'error',
      badge: '错误',
      title: preview(message, 120),
      detail: message,
      time,
      startedMs: at,
      endedMs: at,
    });
    return;
  }
  if (event.type === 'turn.paused') {
    turn.status = 'paused';
    turn.statusLabel = '等待审批';
    return;
  }
  if (event.type === 'turn.end') {
    turn.status = String(event.payload['status'] ?? 'completed');
    turn.statusLabel = trailStatusLabel(turn.status);
    const at = Date.parse(event.created_at);
    turn.endedMs = Number.isNaN(at) ? null : at;
    turn.duration = durationLabel(turn.startedMs, turn.endedMs);
  }
}

/** 模型调用按发生时间归入对应轮次（调用明细没有 turn_seq） */
function attachModelCalls(turns: TrailTurn[], calls: ModelCallItem[]): void {
  for (const call of calls) {
    const at = Date.parse(call.created_at);
    if (Number.isNaN(at)) {
      continue;
    }
    const turn = turns.find(
      (item) => at >= item.startedMs && (item.endedMs === null || at <= item.endedMs),
    );
    if (!turn) {
      continue;
    }
    const latency = call.latency_ms === null ? '' : ` · ${(call.latency_ms / 1000).toFixed(1)}s`;
    const tokens =
      call.prompt_tokens === null
        ? ''
        : ` · ${String(call.prompt_tokens)}+${String(call.completion_tokens ?? 0)} tok`;
    const failed = call.error_code === null ? '' : ` · 错误 ${String(call.error_code)}`;
    turn.nodes.push({
      key: `call-${call.created_at}-${call.model}`,
      kind: 'call',
      badge: '模型',
      title: `${call.model}${latency}${tokens}${failed}`,
      time: formatClock(call.created_at),
      startedMs: call.latency_ms === null ? at : at - call.latency_ms,
      endedMs: at,
      meta: `模型 ${call.model}${latency}${tokens}${failed}`,
    });
  }
}

/** 刻度间隔：取能让刻度数不超过 8 的最小档 */
function pickTickStep(span: number): number {
  return TICK_STEPS.find((step) => span / step <= 8) ?? 3600000;
}

export interface UseTrajectoryOptions {
  events: Ref<SessionEventItem[]>;
  modelCalls: Ref<ModelCallItem[]>;
}

/** 轨迹抽屉：事件与调用明细归并、时间概览框选缩放、虚拟滚动 */
export function useTrajectory({ events, modelCalls }: UseTrajectoryOptions) {
  /** 活动时间线：事件 + 调用明细归并成"轮次 → 节点" */
  const trailTurns = computed<TrailTurn[]>(() => {
    const turns: TrailTurn[] = [];
    let current: TrailTurn | null = null;
    for (const event of events.value) {
      if (event.type === 'turn/start') {
        current = newTrailTurn(turns.length + 1, event);
        turns.push(current);
        continue;
      }
      if (current !== null) {
        appendTrailEvent(current, event);
      }
    }
    attachModelCalls(turns, modelCalls.value);
    for (const turn of turns) {
      turn.nodes.sort((left, right) => (left.startedMs ?? 0) - (right.startedMs ?? 0));
    }
    for (const turn of turns) {
      const starts = turn.nodes
        .map((node) => node.startedMs)
        .filter((value): value is number => value !== undefined);
      const ends = turn.nodes
        .map((node) => node.endedMs ?? node.startedMs)
        .filter((value): value is number => value !== undefined);
      turn.spanStart = starts.length > 0 ? Math.min(...starts) : turn.startedMs;
      turn.spanEnd = ends.length > 0 ? Math.max(...ends) : turn.startedMs;
    }
    return turns;
  });

  /** 选中记录：右侧检查器展示完整细节 */
  const selected = ref<TrailNode | null>(null);

  /** 选中一条记录 */
  function selectNode(node: TrailNode): void {
    selected.value = node;
  }

  /** 全量时间窗：概览默认显示整段会话 */
  const sessionSpan = computed<{ start: number; end: number }>(() => {
    const turns = trailTurns.value;
    if (turns.length === 0) {
      return { start: 0, end: 1 };
    }
    return {
      start: Math.min(...turns.map((turn) => turn.spanStart)),
      end: Math.max(...turns.map((turn) => turn.spanEnd)),
    };
  });

  /** 当前可视时间窗：null 表示全量 */
  const trailDomain = ref<{ start: number; end: number } | null>(null);
  /** 框选区间：非空时记录表只保留与该区间有交集的节点 */
  const trailSelection = ref<{ start: number; end: number } | null>(null);

  /** 时间轴当前域 */
  function activeDomain(): { start: number; end: number } {
    return trailDomain.value ?? sessionSpan.value;
  }

  /** 节点在时间轴上的位置百分比 */
  function spanStyle(node: TrailNode): Record<string, string> {
    const domain = activeDomain();
    const span = Math.max(1, domain.end - domain.start);
    const start = node.startedMs ?? domain.start;
    const end = node.endedMs ?? node.startedMs ?? start;
    return {
      left: `${Math.min(100, Math.max(0, ((start - domain.start) / span) * 100))}%`,
      width: `${Math.min(100, Math.max(0.6, ((end - start) / span) * 100))}%`,
    };
  }

  /** 框选带位置 */
  function bandStyle(selection: { start: number; end: number }): Record<string, string> {
    const domain = activeDomain();
    const span = Math.max(1, domain.end - domain.start);
    return {
      left: `${Math.min(100, Math.max(0, ((selection.start - domain.start) / span) * 100))}%`,
      width: `${Math.min(100, Math.max(0.6, ((selection.end - selection.start) / span) * 100))}%`,
    };
  }

  /** 轨迹默认渲染最近 50 轮，避免超长会话铺满 DOM */
  const trailLimit = ref(50);
  const pagedTrailTurns = computed(() => trailTurns.value.slice(-trailLimit.value));
  const hiddenTrailTurns = computed(() =>
    Math.max(0, trailTurns.value.length - pagedTrailTurns.value.length),
  );

  /** 加载更早的轨迹轮次（每次 50 轮） */
  function showEarlierTrail(): void {
    trailLimit.value += 50;
  }

  /** 记录表按框选过滤 */
  const visibleTurns = computed<TrailTurn[]>(() => {
    const selection = trailSelection.value;
    const base = pagedTrailTurns.value;
    if (selection === null) {
      return base;
    }
    return base
      .map((turn) => ({
        ...turn,
        nodes: turn.nodes.filter((node) => {
          const start = node.startedMs ?? node.endedMs ?? 0;
          const end = node.endedMs ?? node.startedMs ?? start;
          return end >= selection.start && start <= selection.end;
        }),
      }))
      .filter((turn) => turn.nodes.length > 0);
  });

  /** 轨迹表格虚拟滚动：固定行高，只渲染可视窗口附近的记录 */
  const trajRows = computed<TrajRow[]>(() => {
    const rows: TrajRow[] = [];
    for (const turn of visibleTurns.value) {
      rows.push({ key: `${turn.key}-head`, height: TRAJ_HEADER_HEIGHT, turn, node: null });
      for (const node of turn.nodes) {
        rows.push({ key: node.key, height: TRAJ_ROW_HEIGHT, turn, node });
      }
    }
    return rows;
  });

  const trajOffsets = computed<number[]>(() => {
    const offsets = [0];
    for (const row of trajRows.value) {
      offsets.push((offsets.at(-1) ?? 0) + row.height);
    }
    return offsets;
  });

  const trajTotal = computed(() => trajOffsets.value.at(-1) ?? 0);
  const trajScrollTop = ref(0);

  /** 表格滚动：只更新窗口起点，不重排全部行 */
  function onTrajScroll(event: Event): void {
    trajScrollTop.value = (event.target as HTMLElement).scrollTop;
  }

  const trajWindow = computed(() => {
    const rows = trajRows.value;
    const offsets = trajOffsets.value;
    const top = Math.max(0, trajScrollTop.value - 200);
    const bottom = trajScrollTop.value + TRAJ_VIEWPORT + 200;
    let start = 0;
    while (start < rows.length && (offsets[start + 1] ?? 0) <= top) {
      start += 1;
    }
    let end = start;
    while (end < rows.length && (offsets[end] ?? 0) < bottom) {
      end += 1;
    }
    return { rows: rows.slice(start, end), offset: offsets[start] ?? 0 };
  });

  /** 点击虚拟行：标题行不可选 */
  function selectTrajRow(row: TrajRow): void {
    if (row.node !== null) {
      selectNode(row.node);
    }
  }

  let dragAnchor: number | null = null;

  /** 鼠标位置 → 时间 */
  function timeAt(event: MouseEvent): number {
    const root = event.currentTarget as HTMLElement;
    const track = root.querySelector<HTMLElement>('.overview-track') ?? root;
    const rect = track.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / Math.max(1, rect.width)));
    const domain = activeDomain();
    return domain.start + ratio * (domain.end - domain.start);
  }

  function onOverviewDown(event: MouseEvent): void {
    dragAnchor = timeAt(event);
    trailSelection.value = null;
  }

  function onOverviewMove(event: MouseEvent): void {
    if (dragAnchor === null) {
      return;
    }
    const now = timeAt(event);
    trailSelection.value = { start: Math.min(dragAnchor, now), end: Math.max(dragAnchor, now) };
  }

  function onOverviewUp(): void {
    dragAnchor = null;
    const selection = trailSelection.value;
    if (selection !== null && selection.end - selection.start < 500) {
      trailSelection.value = null;
    }
  }

  /** 滚轮缩放时间域（以指针为锚点） */
  function onOverviewWheel(event: WheelEvent): void {
    const full = sessionSpan.value;
    const current = trailDomain.value ?? full;
    const span = Math.max(1, current.end - current.start);
    const factor = event.deltaY < 0 ? 0.8 : 1.25;
    const target = Math.max(1000, Math.min(full.end - full.start, span * factor));
    const anchor = timeAt(event);
    const ratio = (anchor - current.start) / span;
    let start = anchor - target * ratio;
    let end = start + target;
    if (start < full.start) {
      start = full.start;
      end = start + target;
    }
    if (end > full.end) {
      end = full.end;
      start = end - target;
    }
    trailDomain.value = target >= full.end - full.start - 1 ? null : { start, end };
  }

  /** 时间刻度：按当前域自适应间隔 */
  const rulerTicks = computed<{ key: number; left: string; label: string }[]>(() => {
    const domain = activeDomain();
    const span = Math.max(1, domain.end - domain.start);
    const step = pickTickStep(span);
    const ticks: { key: number; left: string; label: string }[] = [];
    for (
      let at = Math.ceil(domain.start / step) * step;
      at <= domain.end && ticks.length < 12;
      at += step
    ) {
      ticks.push({
        key: at,
        left: `${((at - domain.start) / span) * 100}%`,
        label: formatClock(new Date(at).toISOString()),
      });
    }
    return ticks;
  });

  /** 以中心缩放时间域（按钮可达，不依赖滚轮） */
  function zoomTrail(factor: number): void {
    const full = sessionSpan.value;
    const current = trailDomain.value ?? full;
    const span = Math.max(1, current.end - current.start);
    const target = Math.max(1000, Math.min(full.end - full.start, span * factor));
    const center = (current.start + current.end) / 2;
    let start = center - target / 2;
    let end = center + target / 2;
    if (start < full.start) {
      start = full.start;
      end = start + target;
    }
    if (end > full.end) {
      end = full.end;
      start = end - target;
    }
    trailDomain.value = target >= full.end - full.start - 1 ? null : { start, end };
  }

  /** 右键清除框选与缩放 */
  function clearTrailView(): void {
    trailSelection.value = null;
    trailDomain.value = null;
  }

  return {
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
  };
}
