"""契约与健康检查：信封、错误码出口与请求追踪。"""

from fastapi.testclient import TestClient

from lingxi.app.config import Settings
from lingxi.app.main import create_app

app = create_app(Settings())


def test_health_returns_envelope() -> None:
    """健康检查返回统一信封且码值为 0。"""
    with TestClient(app) as client:
        resp = client.get("/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["code"] == 0
        assert body["data"] == "ok"
        assert body["requestId"] != "-"


def test_request_id_passthrough() -> None:
    """传入的 X-Request-Id 透传并回写响应头与信封。"""
    with TestClient(app) as client:
        resp = client.get("/health", headers={"X-Request-Id": "abc123"})
        assert resp.headers["x-request-id"] == "abc123"
        assert resp.json()["requestId"] == "abc123"


def test_api_error_exits_as_envelope_with_200() -> None:
    """业务失败经统一出口：HTTP 恒 200 + 登记错误码。"""

    from fastapi import APIRouter

    from lingxi.contract import ApiError, ErrorCode

    router = APIRouter()

    @router.get("/boom")
    async def boom() -> dict[str, object]:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, "参数不对")

    app.include_router(router)
    with TestClient(app) as client:
        resp = client.get("/boom")
        assert resp.status_code == 200
        body = resp.json()
        assert body["code"] == 60001
        assert body["msg"] == "参数不对"
        assert body["data"] is None
