"""模型能力层：路由打分与冷却。"""

from lingxi.db.models import Model
from lingxi.model_gateway.routing import RoutingState


def _model(model_id: int, rpm: int = 100) -> Model:
    model = Model(
        id=model_id,
        vendor_id=1,
        model_key=f"m-{model_id}",
        display_name=f"m-{model_id}",
        capability="CHAT",
        rpm_limit=rpm,
        tpm_limit=100000,
        features={},
    )
    return model


def test_order_prefers_lower_latency() -> None:
    """容量相同时延迟低的排前面。"""
    state = RoutingState()
    slow, fast = _model(1), _model(2)
    state.record_success(1, 1000, {})
    state.record_success(2, 100, {})
    assert [model.id for model in state.order([slow, fast])] == [2, 1]


def test_failure_cooldown_removes_candidate() -> None:
    """失败后进入冷却，不再参与候选。"""
    state = RoutingState()
    model = _model(3)
    assert state.is_available(model)
    state.record_failure(model)
    assert not state.is_available(model)


def test_capacity_prefers_more_remaining() -> None:
    """已用满 RPM 的模型排到后面。"""
    state = RoutingState()
    used, fresh = _model(4), _model(5)
    for _ in range(100):
        state.acquire(used)
    ordered = state.order([used, fresh])
    assert [model.id for model in ordered] == [5, 4]
