"""进程内路由状态：延迟 EWMA、固定窗口限流、冷却与熔断。"""

import time
from collections import deque
from dataclasses import dataclass

from lingxi.db.models import Model

WINDOW_SECONDS = 60
ALPHA = 0.3
SLOW_START_FLOOR = 0.2
SLOW_START_SECONDS = 45
COOLDOWN_SECONDS = 60
CIRCUIT_WINDOW_SECONDS = 60
CIRCUIT_ERROR_RATIO = 0.5
CIRCUIT_MIN_SAMPLES = 5


@dataclass
class _Window:
    start: float
    used: int


class RoutingState:
    """单进程路由状态；多实例部署时需替换为共享存储。"""

    def __init__(self) -> None:
        self._latency: dict[int, float] = {}
        self._first_seen: dict[int, float] = {}
        self._rpm: dict[int, _Window] = {}
        self._tpm: dict[int, _Window] = {}
        self._failures: dict[int, deque[float]] = {}
        self._cooldown: dict[int, float] = {}
        self._circuit: dict[int, float] = {}

    def _latency_of(self, model_id: int) -> float:
        return self._latency.get(model_id, 0.0)

    def _slow_start(self, model_id: int) -> float:
        now = time.monotonic()
        seen = self._first_seen.setdefault(model_id, now)
        elapsed = now - seen
        if elapsed >= SLOW_START_SECONDS:
            return 1.0
        return SLOW_START_FLOOR + (1 - SLOW_START_FLOOR) * elapsed / SLOW_START_SECONDS

    def _ratio(self, window: _Window | None, limit: int | None) -> float:
        if limit is None or limit <= 0:
            return 1.0
        now = time.monotonic()
        if window is None or now - window.start >= WINDOW_SECONDS:
            return 1.0
        return max(0.0, 1.0 - window.used / limit)

    def capacity(self, model: Model) -> float:
        """剩余容量比例 × 慢启动系数。"""
        ratio = min(
            self._ratio(self._rpm.get(model.id), model.rpm_limit), self._ratio(self._tpm.get(model.id), model.tpm_limit)
        )
        return ratio * self._slow_start(model.id)

    def is_available(self, model: Model) -> bool:
        """未冷却、未熔断、配额未耗尽。"""
        now = time.monotonic()
        if self._cooldown.get(model.id, 0) > now:
            return False
        if self._circuit.get(model.id, 0) > now:
            return False
        if self._ratio(self._rpm.get(model.id), model.rpm_limit) <= 0:
            return False
        return self._ratio(self._tpm.get(model.id), model.tpm_limit) > 0

    def acquire(self, model: Model) -> bool:
        """占用一次 RPM；容量不足返回 False。"""
        if not self.is_available(model):
            return False
        if model.rpm_limit:
            self._bump(self._rpm, model.id, 1)
        return True

    def record_success(self, model_id: int, latency_ms: int, usage: dict[str, int]) -> None:
        """记录延迟与 TPM。"""
        old = self._latency.get(model_id, float(latency_ms))
        self._latency[model_id] = ALPHA * latency_ms + (1 - ALPHA) * old
        tokens = usage.get("total") or (usage.get("prompt", 0) + usage.get("completion", 0))
        if tokens > 0:
            self._bump(self._tpm, model_id, tokens)

    def record_failure(self, model: Model) -> None:
        """记录失败：冷却与熔断。"""
        now = time.monotonic()
        self._cooldown[model.id] = now + COOLDOWN_SECONDS
        window = self._failures.setdefault(model.id, deque())
        window.append(now)
        while window and now - window[0] > CIRCUIT_WINDOW_SECONDS:
            window.popleft()
        if len(window) >= CIRCUIT_MIN_SAMPLES:
            error_ratio = len(window) / max(1, len(window) + 1)
            if error_ratio >= CIRCUIT_ERROR_RATIO:
                self._circuit[model.id] = now + COOLDOWN_SECONDS

    def order(self, models: list[Model]) -> list[Model]:
        """按容量降序、延迟升序。"""
        return sorted(models, key=lambda model: (-self.capacity(model), self._latency_of(model.id)))

    def latency_ms(self, model_id: int) -> int:
        """当前延迟 EWMA。"""
        return int(self._latency.get(model_id, 0))

    def _bump(self, store: dict[int, _Window], model_id: int, amount: int) -> None:
        now = time.monotonic()
        window = store.get(model_id)
        if window is None or now - window.start >= WINDOW_SECONDS:
            store[model_id] = _Window(start=now, used=amount)
        else:
            window.used += amount


routing_state = RoutingState()
