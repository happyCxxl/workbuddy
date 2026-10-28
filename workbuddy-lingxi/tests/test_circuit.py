"""熔断器单元测试：三态转移（不用时间模拟，冷却取 0/大值）。"""

from lingxi.domain.llm.circuit import CircuitBreaker


def test_trips_open_when_window_full_of_failures() -> None:
    """窗口满且失败率达标 → open。"""
    breaker = CircuitBreaker(failure_rate_threshold=0.5, cooldown_seconds=60, window_size=2)
    breaker.record_failure()
    assert breaker.state == "closed"
    breaker.record_failure()
    assert breaker.state == "open"


def test_open_blocks_until_cooldown() -> None:
    """open 期间放行被拒（快速失败语义）。"""
    breaker = CircuitBreaker(failure_rate_threshold=0.5, cooldown_seconds=60, window_size=2)
    breaker.record_failure()
    breaker.record_failure()
    assert breaker.allow() is False


def test_half_open_probe_success_closes() -> None:
    """冷却到期放行一次探测，成功即关闭。"""
    breaker = CircuitBreaker(failure_rate_threshold=0.5, cooldown_seconds=0, window_size=2)
    breaker.record_failure()
    breaker.record_failure()
    assert breaker.state == "open"
    assert breaker.allow() is True
    breaker.record_success()
    assert breaker.state == "closed"


def test_half_open_probe_failure_reopens() -> None:
    """探测失败立即重新打开。"""
    breaker = CircuitBreaker(failure_rate_threshold=0.5, cooldown_seconds=0, window_size=2)
    breaker.record_failure()
    breaker.record_failure()
    assert breaker.allow() is True
    breaker.record_failure()
    assert breaker.state == "open"
