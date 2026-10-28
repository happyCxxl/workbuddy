"""模型出口端口与契约：业务只见统一出口，供应商差异全部封装在适配层。"""

from collections.abc import AsyncIterator
from typing import Literal, Protocol

from pydantic import BaseModel


class ChatMessage(BaseModel):
    """一条对话消息；assistant 消息可携带思考内容（协议外字段，思考模型要求回传）。"""

    role: Literal["system", "user", "assistant"]
    content: str
    reasoning_content: str | None = None


class ChatRequest(BaseModel):
    """对话请求：消息列表。模型选择由出口内部路由决定，不在请求中指定。"""

    messages: list[ChatMessage]


class ToolCall(BaseModel):
    """一次工具调用：参数为 JSON 字符串（协议形态）。"""

    id: str
    name: str
    arguments: str


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
