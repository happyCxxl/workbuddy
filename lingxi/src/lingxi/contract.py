"""对外契约：统一响应信封、错误码集中登记与请求追踪。"""

from contextvars import ContextVar
from enum import Enum

# 请求追踪 ID：由中间件写入，贯穿日志与模型调用明细（PY-DTO-009）
REQUEST_ID: ContextVar[str] = ContextVar("request_id", default="-")


class ErrorCode(Enum):
    """错误码集中登记：AI 域 60xxx 段；新增先登记后使用（PY-ERR-004）。"""

    OK = (0, "ok")
    INTERNAL = (60000, "系统内部错误")
    INVALID_ARGUMENT = (60001, "参数错误")
    NOT_FOUND = (60002, "资源不存在")
    UNAUTHORIZED = (60003, "未认证")
    FORBIDDEN = (60004, "无权限")
    STRUCTURED_OUTPUT_INVALID = (60101, "结构化输出解析失败")
    MODEL_UNAVAILABLE = (60201, "模型出口全部候选不可用")
    TURN_ABORTED = (60202, "回合中止")

    def __init__(self, code: int, default_message: str) -> None:
        self.code = code
        self.default_message = default_message


class ApiError(Exception):
    """业务失败：携带登记的错误码，由全局出口转统一信封。"""

    def __init__(self, error: ErrorCode, message: str | None = None) -> None:
        super().__init__(message or error.default_message)
        self.error = error


class R:
    """统一响应信封：状态码、消息、数据（PY-DTO-004）。"""

    def __init__(self, code: int, message: str, data: object = None) -> None:
        self.code = code
        self.message = message
        self.data = data

    def to_dict(self) -> dict[str, object]:
        """渲染为响应体（与 Java 侧 R 对齐：code/msg/data）：回带请求追踪 ID。"""
        return {
            "code": self.code,
            "msg": self.message,
            "data": self.data,
            "requestId": REQUEST_ID.get(),
        }

    @classmethod
    def ok(cls, data: object = None) -> "R":
        """成功响应：码值 0。"""
        return cls(code=ErrorCode.OK.code, message=ErrorCode.OK.default_message, data=data)

    @classmethod
    def fail(cls, error: ErrorCode, message: str | None = None) -> "R":
        """失败响应：错误码集中登记表的码值。"""
        return cls(code=error.code, message=message or error.default_message, data=None)
