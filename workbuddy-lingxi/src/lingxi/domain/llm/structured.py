"""结构化通道：Responses strict 优先 → json_object 回退 → Pydantic 校验兜底（PY-AI-003）。"""

import json
import logging
from typing import Any, cast

from openai import APIStatusError, AsyncOpenAI
from pydantic import ValidationError

from lingxi.app.config import Settings
from lingxi.contract import ApiError, ErrorCode
from lingxi.domain.ports import ModelT

logger = logging.getLogger(__name__)

# 读超时：结构化判定不能把回合无限期挂住（失败按既有重试/降级路径处理）
_TIMEOUT_SECONDS = 120.0


class StructuredOutput:
    """结构化输出出口：业务传入目标模型类型，拿到校验通过的实例。"""

    def __init__(self, settings: Settings) -> None:
        self._model = settings.llm.chat_model
        self._max_retries = settings.llm.structured_max_retries
        self._client = AsyncOpenAI(
            api_key=settings.llm.api_key or "unset", base_url=settings.llm.base_url, timeout=_TIMEOUT_SECONDS
        )
        self._strict_disabled = False

    async def structured(self, prompt: str, model_type: type[ModelT], model: str | None = None) -> ModelT:
        """产出结构化结果：校验不过重试限次，超限按失败处理。"""
        model_name = model or self._model
        schema: dict[str, object] = model_type.model_json_schema()
        for _attempt in range(self._max_retries):
            text = await self._call(prompt, schema, model_name)
            try:
                return model_type.model_validate_json(text)
            except ValidationError:
                logger.info("结构化输出校验失败，重试")
        raise ApiError(ErrorCode.STRUCTURED_OUTPUT_INVALID, f"结构化输出校验失败（已重试 {self._max_retries} 次）")

    async def _call(self, prompt: str, schema: dict[str, object], model: str) -> str:
        """按通道策略取文本：strict 不可用一次后固定走 json_object。"""
        if not self._strict_disabled:
            try:
                return await self._responses_strict(prompt, schema, model)
            except APIStatusError as exc:
                if exc.status_code >= 500:
                    raise
                logger.warning("strict 结构化输出不可用（%s），回退 json_object", exc.status_code)
                self._strict_disabled = True
            except Exception as exc:
                logger.warning("strict 通道异常（%s），回退 json_object", type(exc).__name__)
                self._strict_disabled = True
        return await self._json_object(prompt, schema, model)

    async def _responses_strict(self, prompt: str, schema: dict[str, object], model: str) -> str:
        """Responses API：解码层强制符合 schema。"""
        response = await self._client.responses.create(
            model=model,
            input=prompt,
            # 协议形态：格式对象按官方结构透传，SDK 类型不含 schema 自由键
            text=cast(Any, {"format": {"type": "json_schema", "name": "result", "schema": schema, "strict": True}}),
        )
        return response.output_text

    async def _json_object(self, prompt: str, schema: dict[str, object], model: str) -> str:
        """json_object 回退：prompt 必须出现 JSON 字样（DeepSeek 口径），结果靠校验兜底。"""
        full_prompt = f"{prompt}\n请输出 JSON，严格符合以下 schema：\n{json.dumps(schema, ensure_ascii=False)}"
        response = await self._client.chat.completions.create(
            model=model,
            messages=cast(Any, [{"role": "user", "content": full_prompt}]),
            response_format={"type": "json_object"},
        )
        return response.choices[0].message.content or ""
