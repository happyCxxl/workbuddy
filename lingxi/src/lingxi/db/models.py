"""数据模型：六张表的 ORM 映射，与 Alembic 迁移同步维护。"""

from datetime import datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import BigInteger, DateTime, Float, ForeignKey, Identity, Integer, String, Text, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    """全部模型的声明基类。"""


class PersonaVersion(Base):
    """人格版本：八字段快照，版本递增、内容不可变。"""

    __tablename__ = "persona_version"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    version: Mapped[int] = mapped_column(Integer, unique=True, nullable=False)
    fields: Mapped[dict[str, object]] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class AiSession(Base):
    """会话：绑定工作目录与人格版本；子会话以 parent_id 表达，删除只改 status。"""

    __tablename__ = "ai_session"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    work_dir: Mapped[str] = mapped_column(Text, nullable=False)
    title: Mapped[str] = mapped_column(String(255), nullable=False, server_default="")
    persona_version_id: Mapped[int | None] = mapped_column(ForeignKey("persona_version.id"), nullable=True)
    parent_id: Mapped[int | None] = mapped_column(ForeignKey("ai_session.id"), nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default="active")
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


class PermissionRule(Base):
    """权限规则：工具 × 作用域 × 动作；层级为全局或按工作目录。"""

    __tablename__ = "permission_rule"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    tool: Mapped[str] = mapped_column(String(64), nullable=False)
    scope: Mapped[str | None] = mapped_column(Text, nullable=True)
    action: Mapped[str] = mapped_column(String(16), nullable=False)
    level: Mapped[str] = mapped_column(String(16), nullable=False)
    work_dir: Mapped[str | None] = mapped_column(Text, nullable=True)
    source: Mapped[str] = mapped_column(String(32), nullable=False, server_default="user")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


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
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    error_code: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
