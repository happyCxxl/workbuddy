import { computed, nextTick, ref } from 'vue';
import type { Ref } from 'vue';

/** 文档块一次渲染多少条：大文档不做一次性全渲染，滚动到底再追加 */
const DEFAULT_STEP = 80;
/** 距底部多远触发追加渲染 */
const DEFAULT_LOAD_MARGIN = 240;

export interface UseDocBlocksOptions<T> {
  /** 待渲染的块（已按左栏档位过滤） */
  blocks: () => T[];
  /** 文档滚动容器（由组件持有，模板 ref 绑定到它） */
  boxRef: Ref<HTMLElement | null>;
  /** 每次追加的条数 */
  step?: number;
  /** 触发追加的距底距离（px） */
  loadMargin?: number;
}

/**
 * 左栏文档的渲染窗口：滚到底追加、按目标块补足、按 key 滚动定位。
 *
 * <p>只认 key：块的具体形态由调用方决定（文档块、切片块共用这套窗口）。
 */
export function useDocBlocks<T extends { key: string }>(options: UseDocBlocksOptions<T>) {
  const step = options.step ?? DEFAULT_STEP;
  const margin = options.loadMargin ?? DEFAULT_LOAD_MARGIN;

  /** 已渲染到第几条（滚动递增；定位时按目标块补足） */
  const renderLimit = ref(step);

  /** 实际渲染出来的块 */
  const rendered = computed(() => options.blocks().slice(0, renderLimit.value));

  /** 滚动到接近底部时追加渲染窗口 */
  function onScroll(event: Event): void {
    const box = event.currentTarget as HTMLElement | null;
    if (box === null || renderLimit.value >= options.blocks().length) {
      return;
    }
    if (box.scrollTop + box.clientHeight >= box.scrollHeight - margin) {
      renderLimit.value = Math.min(options.blocks().length, renderLimit.value + step);
    }
  }

  /** 把渲染窗口补到能覆盖 index（目标块不在窗口里时先补，再定位） */
  function ensureRendered(index: number): void {
    if (index >= renderLimit.value) {
      renderLimit.value = Math.min(options.blocks().length, index + step);
    }
  }

  /** 滚到某个块（块还没渲染时先补窗口，等一帧再滚） */
  async function scrollToBlock(key: string): Promise<void> {
    await nextTick();
    const block = options.boxRef.value?.querySelector(`[data-key="${CSS.escape(key)}"]`);
    if (block instanceof HTMLElement) {
      block.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }

  /** 换运行后渲染窗口回到起点 */
  function reset(): void {
    renderLimit.value = step;
  }

  return { renderLimit, rendered, onScroll, ensureRendered, scrollToBlock, reset };
}
