import { computed, type ComputedRef, type Ref } from 'vue';

import type { ModelCallItem, SessionEventItem } from '@/types/assistant';
import type { TurnUsage } from '@/types/chat';
import { durationLabel, formatClock } from '@/utils/chat-format';

/**
 * 每轮耗时、token 与用量汇总：来自事件时间窗与调用明细。
 * 事件给出 turn/start 与 turn.end 边界，调用明细按时间落入对应窗口。
 */
export function useTurnMeta(
  events: Ref<SessionEventItem[]>,
  modelCalls: Ref<ModelCallItem[]>,
): ComputedRef<Partial<Record<number, TurnUsage>>> {
  return computed<Partial<Record<number, TurnUsage>>>(() => {
    const windows: { seq: number; start: number; end: number }[] = [];
    const result: Partial<Record<number, TurnUsage>> = {};
    const eventUsage: Partial<
      Record<number, { prompt: number; completion: number; cacheHit: number; cacheMiss: number }>
    > = {};
    let current: { seq: number; start: number; end: number } | null = null;
    const empty = (): TurnUsage => ({
      duration: '',
      tokens: 0,
      prompt: 0,
      completion: 0,
      cacheHit: 0,
      cacheMiss: 0,
      models: new Set<string>(),
      calls: [],
    });
    for (const event of events.value) {
      if (event.type === 'turn/start') {
        current = { seq: event.seq, start: Date.parse(event.created_at), end: Number.NaN };
        windows.push(current);
        result[event.seq] = empty();
        continue;
      }
      if (event.type === 'assistant/message' && current !== null) {
        const model = event.payload['model'];
        if (typeof model === 'string' && model) {
          result[current.seq]?.models.add(model);
        }
        const rawUsage = event.payload['usage'];
        if (rawUsage !== null && typeof rawUsage === 'object') {
          const usage = rawUsage as Record<string, unknown>;
          const previous = eventUsage[current.seq] ?? {
            prompt: 0,
            completion: 0,
            cacheHit: 0,
            cacheMiss: 0,
          };
          eventUsage[current.seq] = {
            prompt: previous.prompt + Number(usage['prompt_tokens'] ?? 0),
            completion: previous.completion + Number(usage['completion_tokens'] ?? 0),
            cacheHit: previous.cacheHit + Number(usage['prompt_cache_hit_tokens'] ?? 0),
            cacheMiss: previous.cacheMiss + Number(usage['prompt_cache_miss_tokens'] ?? 0),
          };
        }
      }
      if (event.type === 'turn.end' && current !== null) {
        current.end = Date.parse(event.created_at);
        const entry = result[current.seq] ?? empty();
        result[current.seq] = { ...entry, duration: durationLabel(current.start, current.end) };
        const rawUsage = event.payload['usage'];
        if (rawUsage !== null && typeof rawUsage === 'object') {
          const usage = rawUsage as Record<string, unknown>;
          eventUsage[current.seq] = {
            prompt: Number(usage['prompt_tokens'] ?? 0),
            completion: Number(usage['completion_tokens'] ?? 0),
            cacheHit: Number(usage['prompt_cache_hit_tokens'] ?? 0),
            cacheMiss: Number(usage['prompt_cache_miss_tokens'] ?? 0),
          };
        }
      }
    }
    for (const call of modelCalls.value) {
      const at = Date.parse(call.created_at);
      if (Number.isNaN(at)) {
        continue;
      }
      const window = windows.find(
        (item) => at >= item.start && (Number.isNaN(item.end) || at <= item.end),
      );
      if (window === undefined) {
        continue;
      }
      const key = window.seq;
      const entry = result[key] ?? empty();
      const prompt = call.prompt_tokens ?? 0;
      const completion = call.completion_tokens ?? 0;
      const cacheHit = call.prompt_cache_hit_tokens ?? 0;
      const cacheMiss = call.prompt_cache_miss_tokens ?? 0;
      entry.models.add(call.model);
      entry.calls.push({
        model: call.model,
        tokens: prompt + completion,
        latency: call.latency_ms === null ? '' : `${(call.latency_ms / 1000).toFixed(1)}s`,
        time: formatClock(call.created_at),
      });
      result[key] = {
        ...entry,
        tokens: entry.tokens + prompt + completion,
        prompt: entry.prompt + prompt,
        completion: entry.completion + completion,
        cacheHit: entry.cacheHit + cacheHit,
        cacheMiss: entry.cacheMiss + cacheMiss,
      };
    }
    for (const [rawSeq, usage] of Object.entries(eventUsage)) {
      const seq = Number(rawSeq);
      const entry = result[seq];
      if (entry?.calls.length === 0 && usage !== undefined) {
        result[seq] = {
          ...entry,
          tokens: usage.prompt + usage.completion,
          prompt: usage.prompt,
          completion: usage.completion,
          cacheHit: usage.cacheHit,
          cacheMiss: usage.cacheMiss,
        };
      }
    }
    return result;
  });
}
