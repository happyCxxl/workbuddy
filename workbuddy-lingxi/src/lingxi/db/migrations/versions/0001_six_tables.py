"""六表首版：人格版本、会话、事件日志、记忆、权限规则、模型调用明细。

Revision ID: 0001
Revises:
Create Date: 2026-10-27
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from pgvector.sqlalchemy import Vector
from sqlalchemy.dialects import postgresql

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.create_table(
        "persona_version",
        sa.Column("id", sa.BigInteger(), sa.Identity(), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("fields", postgresql.JSONB(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("version"),
    )
    op.create_table(
        "ai_session",
        sa.Column("id", sa.BigInteger(), sa.Identity(), nullable=False),
        sa.Column("work_dir", sa.Text(), nullable=False),
        sa.Column("title", sa.String(length=255), server_default="", nullable=False),
        sa.Column("persona_version_id", sa.BigInteger(), nullable=True),
        sa.Column("parent_id", sa.BigInteger(), nullable=True),
        sa.Column("status", sa.String(length=16), server_default="active", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["parent_id"], ["ai_session.id"]),
        sa.ForeignKeyConstraint(["persona_version_id"], ["persona_version.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_table(
        "session_event",
        sa.Column("id", sa.BigInteger(), sa.Identity(), nullable=False),
        sa.Column("session_id", sa.BigInteger(), nullable=False),
        sa.Column("seq", sa.Integer(), nullable=False),
        sa.Column("type", sa.String(length=64), nullable=False),
        sa.Column("payload", postgresql.JSONB(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["session_id"], ["ai_session.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("session_id", "seq", name="uq_session_event_session_seq"),
    )
    op.create_index("ix_session_event_session_id", "session_event", ["session_id"])
    op.create_table(
        "memory",
        sa.Column("id", sa.BigInteger(), sa.Identity(), nullable=False),
        sa.Column("kind", sa.String(length=32), nullable=False),
        sa.Column("scope", sa.String(length=16), nullable=False),
        sa.Column("work_dir", sa.Text(), nullable=True),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("source_session_id", sa.BigInteger(), nullable=True),
        sa.Column("source_turn_seq", sa.Integer(), nullable=True),
        sa.Column("confidence", sa.Float(), server_default="1.0", nullable=False),
        sa.Column("embedding", Vector(1024), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_table(
        "permission_rule",
        sa.Column("id", sa.BigInteger(), sa.Identity(), nullable=False),
        sa.Column("tool", sa.String(length=64), nullable=False),
        sa.Column("scope", sa.Text(), nullable=True),
        sa.Column("action", sa.String(length=16), nullable=False),
        sa.Column("level", sa.String(length=16), nullable=False),
        sa.Column("work_dir", sa.Text(), nullable=True),
        sa.Column("source", sa.String(length=32), server_default="user", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_table(
        "model_call",
        sa.Column("id", sa.BigInteger(), sa.Identity(), nullable=False),
        sa.Column("request_id", sa.String(length=64), nullable=False),
        sa.Column("session_id", sa.BigInteger(), nullable=True),
        sa.Column("turn_seq", sa.Integer(), nullable=True),
        sa.Column("step_seq", sa.Integer(), nullable=True),
        sa.Column("model", sa.String(length=128), nullable=False),
        sa.Column("prompt_tokens", sa.Integer(), nullable=True),
        sa.Column("completion_tokens", sa.Integer(), nullable=True),
        sa.Column("latency_ms", sa.Integer(), nullable=True),
        sa.Column("error_code", sa.Integer(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_model_call_request_id", "model_call", ["request_id"])


def downgrade() -> None:
    op.drop_index("ix_model_call_request_id", table_name="model_call")
    op.drop_table("model_call")
    op.drop_table("permission_rule")
    op.drop_table("memory")
    op.drop_index("ix_session_event_session_id", table_name="session_event")
    op.drop_table("session_event")
    op.drop_table("ai_session")
    op.drop_table("persona_version")
