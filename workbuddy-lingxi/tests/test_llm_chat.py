"""对话通道适配器测试：走 conftest 的 fake 服务器，禁真实模型调用。"""

from lingxi.app.config import Settings
from lingxi.domain.llm.chat import DeepSeekAdapter
from lingxi.domain.ports import ChatMessage, ChatRequest


async def collect(adapter: DeepSeekAdapter, messages: list[ChatMessage]) -> list[object]:
    """跑一次流式调用并收集全部 chunk。"""
    return [chunk async for chunk in adapter.stream(ChatRequest(messages=messages, tools=[]))]


def user(content: str) -> ChatMessage:
    """造一条用户消息。"""
    return ChatMessage(role="user", content=content, tool_calls=[])


async def test_stream_text_and_usage_last_chunk_form(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """DeepSeek 形态：usage 挂最后内容 chunk，归一化为末尾 usage 块。"""
    settings, _ = fake_server
    chunks = await collect(DeepSeekAdapter(settings), [user("hi")])
    texts = [c.text for c in chunks if c.kind == "text"]
    assert texts == ["你", "好"]
    assert chunks[-1].kind == "usage"
    assert chunks[-1].usage is not None
    assert (chunks[-1].usage.prompt_tokens, chunks[-1].usage.completion_tokens) == (9, 4)


async def test_usage_standard_form(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """标准形态：usage 在独立空 choices 终块，同样归一为末尾 usage 块。"""
    settings, _ = fake_server
    chunks = await collect(DeepSeekAdapter(settings), [user("usage_standard")])
    assert chunks[-1].kind == "usage"
    assert chunks[-1].usage is not None
    assert (chunks[-1].usage.prompt_tokens, chunks[-1].usage.completion_tokens) == (9, 4)


async def test_tool_call_passthrough(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """工具调用跨 chunk 片段合并为完整 ToolCall。"""
    settings, _ = fake_server
    chunks = await collect(DeepSeekAdapter(settings), [user("tool")])
    tool_chunks = [c for c in chunks if c.kind == "tool_calls"]
    assert len(tool_chunks) == 1
    assert len(tool_chunks[0].tool_calls) == 1
    call = tool_chunks[0].tool_calls[0]
    assert call.id == "call_1"
    assert call.name == "fs_read"
    assert call.arguments == '{"path": "x"}'


async def test_reasoning_streamed_and_echoed_back(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """思考内容流式产出；assistant 消息携带的 reasoning_content 原样回传。"""
    settings, captured = fake_server
    messages = [
        user("上一轮问题"),
        ChatMessage(role="assistant", content="上一轮回答", reasoning_content="上次的思考", tool_calls=[]),
        user("reason"),
    ]
    chunks = await collect(DeepSeekAdapter(settings), messages)
    thinking = [c.text for c in chunks if c.kind == "thinking"]
    assert thinking == ["在想", "你"]
    sent = captured[-1]["messages"]
    assert sent[1]["reasoning_content"] == "上次的思考"
