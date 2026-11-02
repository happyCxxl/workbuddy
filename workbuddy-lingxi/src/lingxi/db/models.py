"""数据模型：业务表的 ORM 映射，与 Alembic 迁移同步维护。"""

from datetime import datetime
from decimal import Decimal

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Identity,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    """全部模型的声明基类。"""


class PersonaVersion(Base):
    """人格版本：八字段快照，版本递增、内容不可变；active 标记新会话默认使用的版本。"""

    __tablename__ = "persona_version"
    __table_args__ = (UniqueConstraint("user_id", "version", name="uq_persona_user_version"),)

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False, server_default="1", default=1)
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    fields: Mapped[dict[str, object]] = mapped_column(JSONB, nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class Workspace(Base):
    """工作区：会话的归属目录（路径唯一），独立存在，即使还没有会话。"""

    __tablename__ = "workspace"
    __table_args__ = (UniqueConstraint("user_id", "path", name="uq_workspace_user_path"),)

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False, server_default="1", default=1)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    path: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class SessionCheckpoint(Base):
    """会话检查点：一个回合一份，files 记录文件快照清单。"""

    __tablename__ = "session_checkpoint"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("ai_session.id"), index=True, nullable=False)
    turn_seq: Mapped[int] = mapped_column(Integer, nullable=False)
    prompt: Mapped[str] = mapped_column(Text, nullable=False, server_default="")
    files: Mapped[list[dict[str, object]]] = mapped_column(JSONB, nullable=False, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    __table_args__ = (Index("ix_session_checkpoint_session_turn", "session_id", "turn_seq"),)


class AiSession(Base):
    """会话：绑定工作目录与人格版本；子会话以 parent_id 表达，删除只改 status。"""

    __tablename__ = "ai_session"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False, server_default="1", default=1)
    model_id: Mapped[int | None] = mapped_column(ForeignKey("model.id"), index=True, nullable=True)
    work_dir: Mapped[str] = mapped_column(Text, nullable=False)
    title: Mapped[str] = mapped_column(String(255), nullable=False, server_default="")
    persona_version_id: Mapped[int | None] = mapped_column(ForeignKey("persona_version.id"), nullable=True)
    workspace_id: Mapped[int | None] = mapped_column(ForeignKey("workspace.id"), nullable=True)
    permission_preset: Mapped[str] = mapped_column(String(16), nullable=False, server_default="standard")
    parent_id: Mapped[int | None] = mapped_column(ForeignKey("ai_session.id"), nullable=True)
    # chat = 用户对话；sub = 后台任务执行器（无记忆、无人格段）
    kind: Mapped[str] = mapped_column(String(8), nullable=False, server_default="chat")
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class SubSession(Base):
    """后台任务：父会话派生的一次性执行任务，记录任务、状态与结果摘要。"""

    __tablename__ = "sub_session"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False, server_default="1", default=1)
    parent_session_id: Mapped[int] = mapped_column(ForeignKey("ai_session.id"), index=True, nullable=False)
    child_session_id: Mapped[int] = mapped_column(ForeignKey("ai_session.id"), unique=True, nullable=False)
    title: Mapped[str] = mapped_column(String(255), nullable=False, server_default="")
    task: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default="running")
    result: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class SessionEvent(Base):
    """事件日志：唯一事实源，append-only；会话内 seq 单调，唯一键防重。"""

    __tablename__ = "session_event"
    __table_args__ = (UniqueConstraint("session_id", "seq", name="uq_session_event_session_seq"),)

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("ai_session.id"), index=True, nullable=False)
    seq: Mapped[int] = mapped_column(Integer, nullable=False)
    type: Mapped[str] = mapped_column(String(64), nullable=False)
    payload: Mapped[dict[str, object]] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class Memory(Base):
    """记忆：三类内容、两级作用域；向量与元数据同行同事务。"""

    __tablename__ = "memory"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False, server_default="1", default=1)
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    scope: Mapped[str] = mapped_column(String(16), nullable=False)
    work_dir: Mapped[str | None] = mapped_column(Text, nullable=True)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    source_session_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    source_turn_seq: Mapped[int | None] = mapped_column(Integer, nullable=True)
    confidence: Mapped[float] = mapped_column(Float, nullable=False, server_default="1.0")
    embedding: Mapped[list[float] | None] = mapped_column(Vector(1024), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class ModelCall(Base):
    """模型调用明细：request_id 对账，按模型与时间检索。"""

    __tablename__ = "model_call"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    request_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    session_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    turn_seq: Mapped[int | None] = mapped_column(Integer, nullable=True)
    step_seq: Mapped[int | None] = mapped_column(Integer, nullable=True)
    model: Mapped[str] = mapped_column(String(128), nullable=False)
    prompt_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    completion_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # 前缀缓存命中/未命中（DeepSeek 自动缓存）：用于核算真实输入成本
    prompt_cache_hit_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    prompt_cache_miss_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    error_code: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class ModelVendor(Base):
    """模型厂商：接入地址与密钥环境变量名，密钥本身不入库。"""

    __tablename__ = "model_vendor"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    code: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    display_name: Mapped[str] = mapped_column(String(128), nullable=False)
    base_url: Mapped[str] = mapped_column(Text, nullable=False)
    api_key: Mapped[str | None] = mapped_column(Text, nullable=True)
    timeout_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    max_retries: Mapped[int | None] = mapped_column(Integer, nullable=True)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true", default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class Model(Base):
    """模型：厂商 + 模型名 + 能力；同模型不同厂商是两条独立记录。"""

    __tablename__ = "model"
    __table_args__ = (
        UniqueConstraint("vendor_id", "model_key", "capability", name="uq_model_vendor_key_cap"),
        Index("ix_model_capability_enabled", "capability", "enabled"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    vendor_id: Mapped[int] = mapped_column(ForeignKey("model_vendor.id"), index=True, nullable=False)
    model_key: Mapped[str] = mapped_column(String(255), nullable=False)
    display_name: Mapped[str] = mapped_column(String(255), nullable=False)
    capability: Mapped[str] = mapped_column(String(16), nullable=False)
    dimension: Mapped[int | None] = mapped_column(Integer, nullable=True)
    context_window: Mapped[int | None] = mapped_column(Integer, nullable=True)
    batch_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    rpm_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    tpm_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    price_in: Mapped[Decimal | None] = mapped_column(Numeric(12, 6), nullable=True)
    price_out: Mapped[Decimal | None] = mapped_column(Numeric(12, 6), nullable=True)
    currency: Mapped[str] = mapped_column(String(8), nullable=False, server_default="CNY")
    features: Mapped[dict[str, object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb"), default=dict
    )
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true", default=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default="UNKNOWN")
    last_checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class ModelGatewayCall(Base):
    """模型能力层调用记录：一次请求最终落一条，失败也记录。"""

    __tablename__ = "model_gateway_call"
    __table_args__ = (
        Index("ix_model_gateway_call_source_time", "source", "created_at"),
        Index("ix_model_gateway_call_model_time", "model_id", "created_at"),
        Index("ix_model_gateway_call_capability_time", "capability", "created_at"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    request_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    source: Mapped[str] = mapped_column(String(64), nullable=False)
    capability: Mapped[str] = mapped_column(String(16), nullable=False)
    model_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    vendor_code: Mapped[str] = mapped_column(String(64), nullable=False, server_default="")
    model_key: Mapped[str] = mapped_column(String(255), nullable=False, server_default="")
    prompt_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    completion_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    total_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    error_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    fallback_used: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
