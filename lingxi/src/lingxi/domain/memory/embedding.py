"""向量化：OpenAI 兼容 embedding API（与模型出口同款接入模式），供应商经配置切换。"""

import logging
from typing import Any, cast

from openai import AsyncOpenAI

from lingxi.app.config import MemoryConfig

logger = logging.getLogger(__name__)


class Embedder:
    """向量化接口：单方法 embed，输出归一化向量（异步）。"""

    async def embed(self, text: str) -> list[float]:
        """文本 → 归一化向量。"""
        raise NotImplementedError


class OpenAICompatibleEmbedder(Embedder):
    """OpenAI 兼容嵌入器：复用 openai SDK；供应商由 base_url/model/key 决定，不落代码。"""

    def __init__(self, config: MemoryConfig) -> None:
        self._model = config.embedding_model
        self._client = AsyncOpenAI(api_key=config.embedding_api_key or "unset", base_url=config.embedding_base_url)

    async def embed(self, text: str) -> list[float]:
        response = await self._client.embeddings.create(
            model=self._model,
            input=cast(Any, text),  # 单条文本形态：SDK 类型要求列表，运行时接受字符串
        )
        vector = response.data[0].embedding
        return [float(value) for value in vector]
