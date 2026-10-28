"""熔断器：滑动窗口失败率 → 打开；冷却后 half-open 单次探测。"""

import time
from collections import deque


class CircuitBreaker:
    """三态熔断：closed 正常放行；open 快速失败；half-open 放行一次探测，结果决定开或关。"""

    def __init__(self, failure_rate_threshold: float, cooldown_seconds: int, window_size: int = 20) -> None:
        self._threshold = failure_rate_threshold
        self._cooldown = cooldown_seconds
        self._window: deque[bool] = deque(maxlen=window_size)
        self._opened_at: float | None = None
        self._probe = False

    @property
    def state(self) -> str:
        """当前状态。"""
        return "open" if self._opened_at is not None else "closed"

    def allow(self) -> bool:
        """是否放行：open 未冷却 → False；冷却到期转 half-open 放行一次探测。"""
        if self._opened_at is not None:
            if time.monotonic() - self._opened_at < self._cooldown:
                return False
            self._opened_at = None
            self._window.clear()
            self._probe = True
        return True

    def record_success(self) -> None:
        """成功回执：half-open 探测成功 → 关闭。"""
        self._window.append(True)
        self._probe = False

    def record_failure(self) -> None:
        """失败回执：half-open 探测失败立即再开；窗口满且失败率达标 → 打开。"""
        if self._probe:
            self._opened_at = time.monotonic()
            self._window.clear()
            self._probe = False
            return
        self._window.append(False)
        if len(self._window) == self._window.maxlen and self._failure_rate >= self._threshold:
            self._opened_at = time.monotonic()

    @property
    def _failure_rate(self) -> float:
        if not self._window:
            return 0.0
        return sum(1 for ok in self._window if not ok) / len(self._window)
