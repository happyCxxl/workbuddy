import { computed, nextTick, ref } from 'vue';
import type { Ref } from 'vue';

import { readSplitRatio, removeSplitRatio, writeSplitRatio } from '@/utils/drawer-split-storage';

/** 左栏最小宽（再窄原文读不了） */
const SPLIT_MIN_LEFT = 320;
/** 右栏最小宽（再窄元素列表不可用） */
const SPLIT_MIN_RIGHT = 360;
/** 方向键每次调整的像素 */
const SPLIT_KEY_STEP = 24;
/** 默认左栏比例（双击恢复到这个值），与样式里的初始宽度同口径 */
const SPLIT_DEFAULT_RATIO = 0.62;

export interface UseSplitterOptions {
  /** 双栏容器引用（由组件持有，模板 ref 绑定到它） */
  bodyRef: Ref<HTMLElement | null>;
  /** 结果文件 id：分隔条比例按此键持久化 */
  knowledgeFileId: () => string;
  /** 任务 id：分隔条比例按此键持久化 */
  taskId: () => string;
  /** 宽度调整后需要重新测量的回调 */
  onResize?: () => void;
}

/**
 * 双栏分隔条：左栏像素宽的测量、拖动、键盘微调与持久化。
 *
 * <p>宽度偏好按「结果文件 + 任务」记住；量不到容器时保持 null，交给样式默认值。
 */
export function useSplitter(options: UseSplitterOptions) {
  /**
   * 左栏宽度（px）。**null 表示还没量过容器**，此时不写内联宽度，
   * 让样式里的默认值（62% / min-width 420px）生效，避免测量前闪一下。
   */
  const sourceWidth = ref<number | null>(null);
  const dragging = ref(false);
  /** 拖动起点：指针 x 与当时的左栏宽 */
  const dragFrom = ref({ pointerX: 0, width: 0 });

  /** 双栏容器当前宽度；量不到时按 0 处理（约束函数会退回最小宽） */
  function bodyWidth(): number {
    return options.bodyRef.value?.clientWidth ?? 0;
  }

  /**
   * 把想要的左栏宽夹进合法区间。
   *
   * <p>两侧最小宽都保证：容器太窄放不下两栏最小宽时，优先保左栏最小宽。
   */
  function clampWidth(width: number): number {
    const total = bodyWidth();
    const maxLeft = Math.max(total - SPLIT_MIN_RIGHT, SPLIT_MIN_LEFT);
    return Math.min(Math.max(Math.round(width), SPLIT_MIN_LEFT), maxLeft);
  }

  /** 左栏占双栏的比例：量不到容器宽度时按默认比例 */
  const splitRatio = computed(() => {
    const total = bodyWidth();
    if (total <= 0 || sourceWidth.value === null) {
      return SPLIT_DEFAULT_RATIO;
    }
    return sourceWidth.value / total;
  });

  const sourcePaneStyle = computed(() =>
    sourceWidth.value === null
      ? undefined
      : { width: `${sourceWidth.value}px`, minWidth: `${SPLIT_MIN_LEFT}px` },
  );

  const splitterTitle = computed(
    () =>
      `拖动调整两栏宽度；双击恢复默认（左栏 ${Math.round(SPLIT_DEFAULT_RATIO * 100)}%）；←/→ 微调`,
  );

  /** 按像素设置左栏宽（夹住后写回，并落到偏好里） */
  function applyWidth(width: number, persist: boolean): void {
    const next = clampWidth(width);
    sourceWidth.value = next;
    if (persist) {
      writeSplitRatio(options.knowledgeFileId(), options.taskId(), next / Math.max(bodyWidth(), 1));
    }
    void nextTick(() => options.onResize?.());
  }

  /** 按默认比例设置左栏宽 */
  function applyDefaultWidth(): void {
    const total = bodyWidth();
    sourceWidth.value = clampWidth(total > 0 ? total * SPLIT_DEFAULT_RATIO : SPLIT_MIN_LEFT);
  }

  /**
   * 拖动开始：记下起点并捕获指针，后续 move/up 都发到分隔条自己身上。
   *
   * <p>`setPointerCapture` 放在 try 里：某些环境（合成事件、指针已被系统接管）
   * 会抛异常，而那不该让整个拖动挂掉 —— 捕获失败时事件仍会冒泡到本元素，
   * 只是指针移出分隔条后可能收不到 move。
   */
  function onSplitterDown(event: PointerEvent): void {
    if (sourceWidth.value === null) {
      applyDefaultWidth();
    }
    dragging.value = true;
    dragFrom.value = { pointerX: event.clientX, width: sourceWidth.value ?? SPLIT_MIN_LEFT };
    try {
      (event.currentTarget as HTMLElement | null)?.setPointerCapture(event.pointerId);
    } catch {
      // 捕获失败：拖动仍能工作，只是指针离开分隔条后可能丢事件
    }
    event.preventDefault();
  }

  function onSplitterMove(event: PointerEvent): void {
    if (!dragging.value) {
      return;
    }
    applyWidth(dragFrom.value.width + (event.clientX - dragFrom.value.pointerX), false);
  }

  function onSplitterUp(event: PointerEvent): void {
    if (!dragging.value) {
      return;
    }
    dragging.value = false;
    const handle = event.currentTarget as HTMLElement | null;
    if (handle?.hasPointerCapture(event.pointerId)) {
      handle.releasePointerCapture(event.pointerId);
    }
    // 拖动结束才落盘：拖动过程中每帧写 localStorage 没有必要
    if (sourceWidth.value !== null) {
      writeSplitRatio(
        options.knowledgeFileId(),
        options.taskId(),
        sourceWidth.value / Math.max(bodyWidth(), 1),
      );
    }
  }

  /** 双击：回到默认比例，并把记录清掉（"默认"与"没记录过"是同一种状态） */
  function resetSplit(): void {
    removeSplitRatio(options.knowledgeFileId(), options.taskId());
    applyDefaultWidth();
  }

  /** 方向键：左右各按固定像素调整，Home/End 到两端 */
  function onSplitterKeydown(event: KeyboardEvent): void {
    const base = sourceWidth.value ?? SPLIT_MIN_LEFT;
    if (event.key === 'ArrowLeft') {
      applyWidth(base - SPLIT_KEY_STEP, true);
    } else if (event.key === 'ArrowRight') {
      applyWidth(base + SPLIT_KEY_STEP, true);
    } else if (event.key === 'Home') {
      applyWidth(SPLIT_MIN_LEFT, true);
    } else if (event.key === 'End') {
      applyWidth(Number.MAX_SAFE_INTEGER, true);
    } else {
      return;
    }
    event.preventDefault();
  }

  /**
   * 量一次容器宽度并定下左栏宽。
   *
   * <p>优先用记住的比例，没有记录就按默认比例算 —— 两者都要过最小宽度约束。
   */
  function measureSplit(): void {
    const total = bodyWidth();
    if (total <= 0) {
      return;
    }
    const remembered = readSplitRatio(options.knowledgeFileId(), options.taskId());
    sourceWidth.value = clampWidth(total * (remembered ?? SPLIT_DEFAULT_RATIO));
  }

  /** 窗口尺寸变化：左栏宽按新容器重新夹一次（保持像素意图，不做比例漂移） */
  function onWindowResize(): void {
    if (dragging.value) {
      return;
    }
    if (sourceWidth.value === null) {
      measureSplit();
      void nextTick(() => options.onResize?.());
      return;
    }
    applyWidth(sourceWidth.value, false);
    void nextTick(() => options.onResize?.());
  }

  return {
    sourceWidth,
    dragging,
    splitRatio,
    sourcePaneStyle,
    splitterTitle,
    onSplitterDown,
    onSplitterMove,
    onSplitterUp,
    resetSplit,
    onSplitterKeydown,
    measureSplit,
    onWindowResize,
  };
}
