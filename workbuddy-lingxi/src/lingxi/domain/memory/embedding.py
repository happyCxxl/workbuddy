"""向量化：优先走模型能力层，未配置向量模型时回落到直连 embedding API。"""

import logging
from typing import Any, cast
from uuid import uuid4

from openai import AsyncOpenAI

from lingxi.app.config import MemoryConfig

logger = logging.getLogger(__name__)

# 超时：向量化失败本来就走降级（仅关键词召回），不能让召回/抽取把回合拖住
_TIMEOUT_SECONDS = 30.0


class Embedder:
    """向量化接口：文档与查询分开，查询可带指令前缀。"""

    async def embed(self, text: str) -> list[float]:
        """文档文本 → 向量。"""
        raise NotImplementedError

    async def embed_query(self, text: str) -> list[float]:
        """查询文本 → 向量；默认与文档一致。"""
        return await self.embed(text)


class OpenAICompatibleEmbedder(Embedder):
    """OpenAI 兼容嵌入器：能力层未配置向量模型时的回落通道。"""

    def __init__(self, config: MemoryConfig) -> None:
        self._model = config.embedding_model
        self._client = AsyncOpenAI(
            api_key=config.embedding_api_key or "unset",
            base_url=config.embedding_base_url,
            timeout=_TIMEOUT_SECONDS,
        )

    async def embed(self, text: str) -> list[float]:
        response = await self._client.embeddings.create(
            model=self._model,
            input=cast(Any, text),  # 单条文本形态：SDK 类型要求列表，运行时接受字符串
        )
        vector = response.data[0].embedding
        return [float(value) for value in vector]


class GatewayEmbedder(Embedder):
    """能力层嵌入器：优先走能力层，失败或未配置时回落直连。"""

    def __init__(self, config: MemoryConfig) -> None:
        self._fallback = OpenAICompatibleEmbedder(config)
        self._dimensions = config.embedding_dimensions

    async def _call(self, text: str, input_type: str) -> list[float]:
        from lingxi.model_gateway import service
        from lingxi.model_gateway.service import GatewayError

        try:
            result, _meta = await service.embed(
                request_id=uuid4().hex,
                source="memory.embed",
                model_id=None,
                texts=[text],
                dimensions=self._dimensions,
                input_type=input_type,
            )
            return result.vectors[0]
        except GatewayError:
            return await self._fallback.embed(text)

    async def embed(self, text: str) -> list[float]:
        return await self._call(text, "document")

    async def embed_query(self, text: str) -> list[float]:
        return await self._call(text, "query")
