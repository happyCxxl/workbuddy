"""模型出口端口与契约：业务只见统一出口，供应商差异全部封装在适配层。"""

from collections.abc import AsyncIterator
from typing import Literal, Protocol, TypeVar

from pydantic import BaseModel

ModelT = TypeVar("ModelT", bound=BaseModel)


class ToolCall(BaseModel):
    """一次工具调用：参数为 JSON 字符串（协议形态）。"""

    id: str
    name: str
    arguments: str


class ToolSpec(BaseModel):
    """工具规格：schema 白名单条目，模型侧工具清单只从注册表生成。"""

    name: str
    description: str
    parameters: dict[str, object]


class ChatMessage(BaseModel):
    """一条对话消息；assistant 可携带思考内容与工具调用，tool 消息携带调用回执。"""

    role: Literal["system", "user", "assistant", "tool"]
    content: str
    reasoning_content: str | None = None
    tool_call_id: str | None = None
    tool_calls: list[ToolCall]


class ChatRequest(BaseModel):
    """对话请求：消息列表 + 工具清单。模型选择由出口内部路由决定，不在请求中指定。"""

    messages: list[ChatMessage]
    tools: list[ToolSpec]


class Usage(BaseModel):
    """token 用量。"""

    prompt_tokens: int = 0
    completion_tokens: int = 0


class StreamChunk(BaseModel):
    """流式增量：kind 区分正文/思考/工具/用量；tool_calls 与 usage 只出现在收尾块，usage 恒为最后一块。"""

    kind: Literal["text", "thinking", "tool_calls", "usage"]
    text: str = ""
    tool_calls: list[ToolCall]
    usage: Usage | None = None


class LlmPort(Protocol):
    """模型出口端口：编排层只依赖此协议。"""

    def stream(self, request: ChatRequest) -> AsyncIterator[StreamChunk]:
        """流式对话：产出归一化的增量块。"""
        ...

    def structured(self, prompt: str, model_type: type[ModelT]) -> ModelT:
        """结构化输出：目标模型类型进，校验通过的实例出（PY-AI-003）。"""
        ...
